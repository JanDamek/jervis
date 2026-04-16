"""Shared helpers for orchestrator nodes.

Contains LLM call wrappers, JSON parsing, cloud escalation logic.

Hardening:
- W-14: Context overflow guard — truncate messages if they exceed tier limit
- W-18: Thread-safe cache via asyncio.Lock (if global caches are added)
"""

from __future__ import annotations

import json
import logging
import re

from langgraph.types import interrupt

from app.config import (
    foreground_headers,
    estimate_tokens,
    LOCAL_CONTEXT_LIMIT,
    DEFAULT_TIER_CONTEXT,
    CONTENT_TRUNCATION_CHARS,
)
from app.llm.provider import llm_provider, TIER_CONFIG
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
    return foreground_headers(state.get("processing_mode", "BACKGROUND"))


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
    "použij openrouter", "použi openrouter",
]


def detect_cloud_prompt(query: str) -> bool:
    """Detect if user explicitly requested cloud model usage."""
    return any(kw in query.lower() for kw in _CLOUD_KEYWORDS)


def auto_providers(rules: ProjectRules) -> set[str]:
    """Build set of auto-enabled cloud providers from project rules.

    OpenRouter is unrestricted — when enabled, it can handle any task type
    and any context size, routing via the priority model list in settings.
    """
    providers = set()
    if rules.auto_use_anthropic:
        providers.add("anthropic")
    if rules.auto_use_openai:
        providers.add("openai")
    if rules.auto_use_gemini:
        providers.add("gemini")
    if rules.max_openrouter_tier != "NONE":
        providers.add("openrouter")
    return providers


def _get_available_providers() -> set[str]:
    """Providers with API keys configured (can be used via user approval)."""
    from app.config import settings as _s
    available = set()
    if _s.anthropic_api_key:
        available.add("anthropic")
    if _s.openai_api_key:
        available.add("openai")
    if _s.google_api_key:
        available.add("gemini")
    if _s.openrouter_api_key:
        available.add("openrouter")
    return available


def _suggest_cloud_tier(
    context_tokens: int,
    providers: set[str],
    task_type: str,
) -> ModelTier | None:
    """Suggest best cloud tier based on enabled providers and task type."""
    # OpenRouter can handle everything — preferred first fallback
    if "openrouter" in providers:
        return ModelTier.CLOUD_OPENROUTER
    # Large context → Gemini only
    if context_tokens > 40_000:
        return ModelTier.CLOUD_LARGE_CONTEXT if "gemini" in providers else None
    has_anthropic = "anthropic" in providers
    has_openai = "openai" in providers
    if has_anthropic and has_openai:
        if task_type in ("architecture", "design_review", "decomposition"):
            return ModelTier.CLOUD_REASONING
        return ModelTier.CLOUD_CODING
    if has_anthropic:
        return ModelTier.CLOUD_REASONING
    if has_openai:
        return ModelTier.CLOUD_CODING
    return None


async def llm_with_cloud_fallback(
    state: dict,
    messages: list,
    context_tokens: int = 0,
    task_type: str = "general",
    max_tokens: int = 8192,
    temperature: float = 0.1,
    tools: list | None = None,
) -> object:
    """Call LLM through the router — router decides local vs cloud + model.

    Intent is derived from `state["processing_mode"]`:
      FOREGROUND  → "orchestrator" (user waiting; router prefers cloud)
      BACKGROUND  → "background"   (nightly work; router prefers GPU)
      QUALIFICATION → "qualifier"  (same as background, GPU OK)

    On empty / failed response we retry once by adding the failed model to
    the X-Skip-Models header so the router picks the next one. On a hard
    failure the call propagates to `_escalate_to_cloud` which asks the user
    via interrupt for explicit approval when needed.
    """
    from app.llm.router_client import report_model_error, report_model_success

    rules = ProjectRules(**state["rules"])
    task = CodingTask(**state["task"])
    allow_cloud_prompt = state.get("allow_cloud_prompt", False)
    processing_mode = state.get("processing_mode", "BACKGROUND")
    client_id = task.client_id

    auto = auto_providers(rules)
    if allow_cloud_prompt:
        auto = auto | _get_available_providers()

    max_tier = rules.max_openrouter_tier if rules.max_openrouter_tier else "NONE"

    intent_map = {
        "FOREGROUND": "orchestrator",
        "QUALIFICATION": "qualifier",
        "BACKGROUND": "background",
    }
    intent = intent_map.get(processing_mode, "background")
    capability = task.capability or "chat"

    # Context overflow guard — the router also enforces a 40k safe budget on
    # local models, but truncating here keeps the payload small when the
    # router does end up on local. Use LOCAL_STANDARD num_ctx as the ceiling.
    tier_config = TIER_CONFIG.get(ModelTier.LOCAL_STANDARD, {})
    local_ctx_limit = tier_config.get("num_ctx", DEFAULT_TIER_CONTEXT)
    if context_tokens > local_ctx_limit:
        logger.info(
            "CONTEXT_OVERFLOW_HINT | estimated=%d > local limit=%d — router will escalate to cloud",
            context_tokens, local_ctx_limit,
        )
        if max_tier == "NONE":
            messages = _truncate_messages_to_budget(messages, local_ctx_limit - max_tokens)

    def _build_headers(skip: list[str] | None) -> dict[str, str]:
        h = dict(priority_headers(state))
        h["X-Intent"] = intent
        h["X-Max-Tier"] = max_tier
        if skip:
            h["X-Skip-Models"] = ",".join(skip)
        return h

    skip_models: list[str] = []
    last_err: Exception | None = None

    for attempt in range(3):
        try:
            logger.info(
                "llm_with_cloud_fallback: attempt=%d capability=%s intent=%s max_tier=%s tokens=%d skip=%s",
                attempt + 1, capability, intent, max_tier, context_tokens, skip_models,
            )
            response = await llm_provider.completion(
                messages=messages,
                capability=capability,
                deadline_iso=task.deadline_iso,
                priority=task.priority,
                client_id=client_id,
                max_tier=max_tier,
                min_model_size=task.min_model_size,
                max_tokens=max_tokens,
                temperature=temperature,
                tools=tools,
                extra_headers=_build_headers(skip_models),
            )
            message = response.choices[0].message
            content = message.content
            tool_calls = getattr(message, "tool_calls", None)
            used_model = getattr(response, "model", "") or ""

            if (not content or not content.strip()) and not tool_calls:
                if used_model:
                    await report_model_error(used_model, "Empty response")
                    if used_model not in skip_models:
                        skip_models.append(used_model)
                raise ValueError(f"Empty response from {used_model or 'LLM'}")

            if used_model:
                await report_model_success(used_model)
            return response

        except Exception as e:
            last_err = e
            logger.warning("llm_with_cloud_fallback attempt=%d failed: %s", attempt + 1, e)

    logger.warning("llm_with_cloud_fallback: exhausted retries (skip=%s), escalating", skip_models)
    return await _escalate_to_cloud(
        task, auto, context_tokens, task_type,
        messages, max_tokens, temperature, tools,
        reason=f"Router LLM calls failed: {str(last_err)[:200]}",
    )


async def _escalate_to_cloud(
    task: CodingTask,
    auto_providers_set: set[str],
    context_tokens: int,
    task_type: str,
    messages: list,
    max_tokens: int,
    temperature: float,
    tools: list | None,
    reason: str,
) -> object:
    """Escalate to cloud with auto/interrupt logic."""

    # 1. Try auto-enabled providers — router picks the concrete model.
    auto_tier = _suggest_cloud_tier(context_tokens, auto_providers_set, task_type)
    if auto_tier:
        logger.info("Auto-escalating to %s (auto_providers=%s)", auto_tier.value, auto_providers_set)
        tier_hint = {
            ModelTier.CLOUD_OPENROUTER: "FREE",
            ModelTier.CLOUD_REASONING: "PAID",
            ModelTier.CLOUD_CODING: "PAID",
            ModelTier.CLOUD_LARGE_CONTEXT: "PAID",
            ModelTier.CLOUD_PREMIUM: "PREMIUM",
        }.get(auto_tier, "FREE")
        return await llm_provider.completion(
            messages=messages,
            capability=task.capability or "chat",
            deadline_iso=task.deadline_iso,
            priority=task.priority,
            client_id=task.client_id,
            max_tier=tier_hint,
            max_tokens=max_tokens,
            temperature=temperature,
            tools=tools,
            extra_headers={"X-Intent": "orchestrator"},
        )

    # 2. Check if any provider is available at all (has API key)
    all_available = _get_available_providers()
    available_tier = _suggest_cloud_tier(context_tokens, all_available, task_type)
    if not available_tier:
        raise RuntimeError(
            f"{reason}. Žádný cloud provider není nakonfigurován (chybí API klíče)."
        )

    # 3. Ask user via interrupt
    tier_label = {
        ModelTier.CLOUD_OPENROUTER: "OpenRouter (routing dle prioritního seznamu)",
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
    tier_hint = {
        ModelTier.CLOUD_OPENROUTER: "FREE",
        ModelTier.CLOUD_REASONING: "PAID",
        ModelTier.CLOUD_CODING: "PAID",
        ModelTier.CLOUD_LARGE_CONTEXT: "PAID",
        ModelTier.CLOUD_PREMIUM: "PREMIUM",
    }.get(available_tier, "PAID")
    return await llm_provider.completion(
        messages=messages,
        capability=task.capability or "chat",
        deadline_iso=task.deadline_iso,
        priority=task.priority,
        client_id=task.client_id,
        max_tier=tier_hint,
        max_tokens=max_tokens,
        temperature=temperature,
        tools=tools,
        extra_headers={"X-Intent": "orchestrator"},
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

    from app.memory.content_reducer import trim_for_display
    logger.warning("Failed to parse JSON from LLM response: %s", trim_for_display(content, 200))
    return {}


# --- Tool loop detection ---


def detect_tool_loop(
    tool_call_history: list[tuple[str, str]],
    tool_name: str,
    arguments: dict,
) -> tuple[str | None, int]:
    """Track tool calls and detect repeated invocations with identical args.

    Appends the call to *tool_call_history* (mutates in-place) and returns a
    tuple of (stop-reason string, repeat_count).  The reason is non-None when
    the same (name, args) pair appears ≥ 2 times.

    When repeat_count >= 3 the caller should REMOVE the tool from available
    tools to prevent the LLM from calling it again (the error message alone
    is not enough — smaller models ignore it).
    """
    call_key = (tool_name, json.dumps(arguments, sort_keys=True))
    tool_call_history.append(call_key)
    repeat_count = tool_call_history.count(call_key)
    if repeat_count >= 2:
        logger.warning("Tool loop detected: %s called %dx with same args", tool_name, repeat_count)
        return (
            f"STOP: Voláš {tool_name} opakovaně se stejnými argumenty. "
            f"Tento nástroj ti nedá jiný výsledek. "
            f"Odpověz uživateli na základě informací které už máš."
        ), repeat_count
    return None, repeat_count


# --- W-14: Context overflow guard ---


def _truncate_messages_to_budget(messages: list, token_budget: int) -> list:
    """Truncate messages to fit within token budget.

    Strategy:
    - Always keep system message (first) and last user message
    - Remove oldest tool results first (they're bulkiest)
    - Then remove oldest assistant/tool messages
    - Never remove the last 4 messages (recent conversation)
    """
    def _estimate_tokens(msg) -> int:
        return estimate_tokens(str(msg.get("content", "")))

    total = sum(_estimate_tokens(m) for m in messages)
    if total <= token_budget:
        return messages

    # Protect first (system) and last 4 messages
    protected_head = 1
    protected_tail = min(4, len(messages) - 1)
    removable_start = protected_head
    removable_end = len(messages) - protected_tail

    if removable_start >= removable_end:
        return messages  # Nothing to remove

    # Remove from oldest, prioritizing tool results
    result = list(messages)
    removed = 0
    # No content truncation — results must never be trimmed.
    # If over budget, remove middle messages entirely (whole messages, not partial content).
    if total > token_budget:
        new_result = result[:protected_head]
        for i in range(protected_head, len(result) - protected_tail):
            if total <= token_budget:
                new_result.extend(result[i:len(result) - protected_tail])
                break
            total -= _estimate_tokens(result[i])
        new_result.extend(result[-protected_tail:])
        result = new_result

    logger.info(
        "CONTEXT_TRUNCATED | original=%d msgs | result=%d msgs | saved≈%d tokens",
        len(messages), len(result), sum(_estimate_tokens(m) for m in messages) - sum(_estimate_tokens(m) for m in result),
    )
    return result


# --- Agent selection logic ---

from app.models import AgentType, Complexity, ProjectRules


def select_agent(
    complexity: Complexity,
    preference: str = "auto",
    rules: ProjectRules | None = None,
) -> AgentType:
    """Select coding agent based on task complexity and project rules.

    Routing logic:
    - Explicit user preference always wins (claude/kilo).
    - KILO selected when ALL of:
        a) complexity is SIMPLE
        b) project has max_openrouter_tier == "FREE" or "NONE"
           (i.e. no paid cloud budget → use free KILO)
    - Claude selected for MEDIUM/COMPLEX/CRITICAL or when paid tier available.
    - If KILO fails, caller should retry with preference="claude" (fallback).
    """
    # Explicit preference overrides automatic selection
    if preference and preference != "auto":
        try:
            return AgentType(preference)
        except ValueError:
            logger.warning("Unknown agent preference %r, falling back to Claude", preference)
            return AgentType.CLAUDE

    # Auto-select based on complexity and project tier
    tier = (rules.max_openrouter_tier if rules else "NONE").upper()

    # KILO for simple tasks on free/no-budget projects
    if complexity == Complexity.SIMPLE and tier in ("NONE", "FREE"):
        logger.info("AUTO_AGENT_SELECT | kilo | complexity=%s, tier=%s", complexity.value, tier)
        return AgentType.KILO

    # Claude for everything else (medium+, or paid projects where quality matters)
    logger.info("AUTO_AGENT_SELECT | claude | complexity=%s, tier=%s", complexity.value, tier)
    return AgentType.CLAUDE
