"""Shared helpers for orchestrator nodes.

Contains LLM call wrappers, JSON parsing, cloud escalation logic.
"""

from __future__ import annotations

import json
import logging
import re

from langgraph.types import interrupt

from app.llm.provider import llm_provider
from app.models import (
    CodingTask,
    ModelTier,
    ProjectRules,
)

logger = logging.getLogger(__name__)


# --- Priority headers for Ollama Router ---


def priority_headers(state: dict) -> dict[str, str]:
    """Return X-Ollama-Priority headers based on processing_mode in state.

    FOREGROUND → CRITICAL (header "0") — user is waiting
    BACKGROUND → no header (router defaults to NORMAL)
    """
    if state.get("processing_mode") == "FOREGROUND":
        return {"X-Ollama-Priority": "0"}
    return {}


# --- Chat history filtering ---


def is_error_message(content: str) -> bool:
    """Check if message content is an error message that should be filtered out.

    Error messages have these patterns:
    - JSON with "error" key: {"error": {"type": "...", "message": "..."}}
    - Plain text starting with "Error:" or "ERROR:"
    - Contains "llm_call_failed" or "Operation not allowed"
    """
    if not content or not isinstance(content, str):
        return False

    content_lower = content.lower().strip()

    # JSON error object
    if content_lower.startswith("{") and '"error"' in content_lower:
        return True

    # Plain text errors
    if content_lower.startswith("error:") or content_lower.startswith("chyba:"):
        return True

    # Specific error signatures
    if "llm_call_failed" in content_lower:
        return True
    if "operation not allowed" in content_lower:
        return True

    return False


# --- Cloud escalation helpers ---

_CLOUD_KEYWORDS = [
    "use cloud", "použi cloud", "použij cloud",
    "cloud model", "cloud modely",
    "použij anthropic", "použi anthropic",
    "použij openai", "použi openai",
]


def detect_cloud_prompt(query: str) -> bool:
    """Detect if user explicitly requested cloud model usage."""
    return any(kw in query.lower() for kw in _CLOUD_KEYWORDS)


def auto_providers(rules: ProjectRules) -> set[str]:
    """Build set of auto-enabled cloud providers from project rules."""
    providers = set()
    if rules.auto_use_anthropic:
        providers.add("anthropic")
    if rules.auto_use_openai:
        providers.add("openai")
    if rules.auto_use_gemini:
        providers.add("gemini")
    return providers


async def llm_with_cloud_fallback(
    state: dict,
    messages: list,
    context_tokens: int = 0,
    task_type: str = "general",
    max_tokens: int = 8192,
    temperature: float = 0.1,
    tools: list | None = None,
) -> object:
    """Call LLM: local first, cloud fallback with policy checks."""
    rules = ProjectRules(**state["rules"])
    task = CodingTask(**state["task"])
    allow_cloud_prompt = state.get("allow_cloud_prompt", False)
    escalation = llm_provider.escalation

    auto = auto_providers(rules)
    if allow_cloud_prompt:
        auto = auto | escalation.get_available_providers()

    # Safety: context exceeds qwen3 max (256k)?
    if context_tokens > 256_000:
        # Try cloud if enabled, otherwise fail
        if auto:
            return await _escalate_to_cloud(
                task, auto, escalation, context_tokens, task_type,
                messages, max_tokens, temperature, tools,
                reason=f"Context příliš velký ({context_tokens//1000}k tokenů, qwen3 max je 256k)",
            )
        raise RuntimeError(
            f"Context příliš velký ({context_tokens//1000}k tokenů, qwen3 max je 256k). "
            "Cloud modely nejsou povoleny v projektu."
        )

    # Priority headers for Ollama Router (FOREGROUND → CRITICAL)
    headers = priority_headers(state)

    # Try local first (qwen3 supports up to 256k context)
    local_tier = escalation.select_local_tier(context_tokens)
    logger.debug("llm_with_cloud_fallback: trying local tier=%s, tools=%s, headers=%s", local_tier.value, bool(tools), headers)
    try:
        response = await llm_provider.completion(
            messages=messages, tier=local_tier,
            max_tokens=max_tokens, temperature=temperature, tools=tools,
            extra_headers=headers,
        )
        message = response.choices[0].message
        content = message.content
        tool_calls = getattr(message, "tool_calls", None)

        logger.debug(
            "llm_with_cloud_fallback: local response - has_content=%s, content_len=%d, has_tool_calls=%s",
            bool(content and content.strip()), len(content or ""), bool(tool_calls)
        )

        # Valid response = has content OR has tool_calls
        if (not content or not content.strip()) and not tool_calls:
            raise ValueError("Empty response from local model")
        return response
    except Exception as e:
        logger.warning("Local LLM failed (tier=%s): %s", local_tier.value, e)
        logger.debug("Local LLM exception details:", exc_info=True)
        return await _escalate_to_cloud(
            task, auto, escalation, context_tokens, task_type,
            messages, max_tokens, temperature, tools,
            reason=f"Lokální model selhal: {str(e)[:200]}",
        )


async def _escalate_to_cloud(
    task: CodingTask,
    auto_providers_set: set[str],
    escalation,
    context_tokens: int,
    task_type: str,
    messages: list,
    max_tokens: int,
    temperature: float,
    tools: list | None,
    reason: str,
) -> object:
    """Escalate to cloud with auto/interrupt logic."""

    # 1. Try auto-enabled providers
    auto_tier = escalation.suggest_cloud_tier(context_tokens, auto_providers_set, task_type)
    if auto_tier:
        logger.info("Auto-escalating to %s (auto_providers=%s)", auto_tier.value, auto_providers_set)
        return await llm_provider.completion(
            messages=messages, tier=auto_tier,
            max_tokens=max_tokens, temperature=temperature, tools=tools,
        )

    # 2. Check if any provider is available at all (has API key)
    available_tier = escalation.best_available_cloud_tier(context_tokens, task_type)
    if not available_tier:
        raise RuntimeError(
            f"{reason}. Žádný cloud provider není nakonfigurován (chybí API klíče)."
        )

    # 3. Ask user via interrupt
    tier_label = {
        ModelTier.CLOUD_REASONING: "Anthropic Claude (reasoning)",
        ModelTier.CLOUD_CODING: "OpenAI GPT-4o (code)",
        ModelTier.CLOUD_LARGE_CONTEXT: "Google Gemini (large context)",
        ModelTier.CLOUD_PREMIUM: "Anthropic Opus (premium)",
    }.get(available_tier, available_tier.value)

    approval = interrupt({
        "type": "approval_request",
        "action": "cloud_model",
        "description": f"{reason}\nPovolit použití cloud modelu {tier_label}?",
        "task_id": task.id,
        "cloud_tier": available_tier.value,
    })

    if not approval.get("approved", False):
        raise RuntimeError(f"{reason}. Uživatel zamítl eskalaci na cloud.")

    logger.info("Cloud escalation approved by user → %s", available_tier.value)
    return await llm_provider.completion(
        messages=messages, tier=available_tier,
        max_tokens=max_tokens, temperature=temperature, tools=tools,
    )


def parse_json_response(content: str) -> dict:
    """Parse JSON from LLM response, handling markdown code blocks.

    LLMs often wrap JSON in ```json ... ``` blocks. This helper
    tries direct parse first, then extracts from code blocks.
    """
    # 1. Try direct JSON parse
    try:
        return json.loads(content)
    except (json.JSONDecodeError, TypeError):
        pass

    # 2. Try extracting from markdown code block
    match = re.search(r"```(?:json)?\s*\n?(.*?)\n?\s*```", content, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except (json.JSONDecodeError, TypeError):
            pass

    # 3. Try finding JSON object in the content
    brace_start = content.find("{")
    brace_end = content.rfind("}")
    if brace_start != -1 and brace_end > brace_start:
        try:
            return json.loads(content[brace_start:brace_end + 1])
        except (json.JSONDecodeError, TypeError):
            pass

    logger.warning("Failed to parse JSON from LLM response: %s", content[:200])
    return {}


# --- Agent selection logic ---

from app.models import AgentType, Complexity, ProjectRules


def select_agent(
    complexity: Complexity,
    preference: str = "auto",
    rules: ProjectRules | None = None,
) -> AgentType:
    """Select coding agent based on task complexity and project rules.

    Strategy:
    - IF cloud_allowed (rules.auto_use_anthropic=True):
      → VŠECHNO řeší Claude (SIMPLE, MEDIUM, COMPLEX, CRITICAL)
    - ELSE (lokální default):
      → SIMPLE: Aider (lokální Ollama, rychlé malé opravy)
      → MEDIUM: OpenHands (lokální Ollama, levné zpracování)
      → COMPLEX: OpenHands (lokální Ollama, větší analýzy)
      → CRITICAL: Claude (TOP agent, nejlepší cena/výkon)
    - Junie: Pouze když explicitně povoleno v projektu (premium, horší než Claude)
    """
    if preference != "auto":
        # Uživatel explicitně zvolil agenta (včetně "junie" pro premium projekty)
        return AgentType(preference)

    # Cloud allowed → všechno řeší Claude
    cloud_allowed = rules.auto_use_anthropic if rules else False
    if cloud_allowed:
        return AgentType.CLAUDE  # Claude pro VŠE když je cloud allowed

    # Lokální default strategie
    match complexity:
        case Complexity.SIMPLE:
            return AgentType.AIDER        # Malé opravy, rychlé zjištění stavu
        case Complexity.MEDIUM:
            return AgentType.OPENHANDS    # Levné zpracování, lokální
        case Complexity.COMPLEX:
            return AgentType.OPENHANDS    # Větší analýzy, lokální
        case Complexity.CRITICAL:
            return AgentType.CLAUDE       # TOP agent pro kritické úkoly
    return AgentType.CLAUDE  # Fallback na nejlepšího agenta
