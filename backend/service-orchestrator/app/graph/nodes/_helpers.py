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
    """Call LLM using router's /route-decision for smart GPU/cloud routing.

    Background: GPU first if free + context ≤48k, else OpenRouter FREE queue.
    NONE tier: always local GPU.
    Cloud fallback on any local failure (timeout, empty response, error).
    """
    from app.llm.router_client import route_request, report_model_error, report_model_success

    rules = ProjectRules(**state["rules"])
    task = CodingTask(**state["task"])
    allow_cloud_prompt = state.get("allow_cloud_prompt", False)
    processing_mode = state.get("processing_mode", "BACKGROUND")

    auto = auto_providers(rules)
    if allow_cloud_prompt:
        auto = auto | _get_available_providers()

    max_tier = rules.max_openrouter_tier if rules.max_openrouter_tier else "NONE"

    # Ask router for routing decision (GPU vs OpenRouter)
    route = await route_request(
        capability="chat",
        max_tier=max_tier,
        estimated_tokens=context_tokens,
        processing_mode=processing_mode,
    )
    logger.info(
        "llm_with_cloud_fallback: route=%s/%s (tokens=%d, mode=%s, max_tier=%s)",
        route.target, route.model, context_tokens, processing_mode, max_tier,
    )

    # Route decision: OpenRouter → use cloud directly (GPU busy or context >48k)
    if route.target == "openrouter" and route.model:
        try:
            response = await llm_provider.completion(
                messages=messages,
                tier=ModelTier.CLOUD_OPENROUTER,
                max_tokens=max_tokens,
                temperature=temperature,
                tools=tools,
                model_override=route.model,
                api_key_override=route.api_key,
            )
            message = response.choices[0].message
            content = message.content
            tool_calls = getattr(message, "tool_calls", None)
            if (not content or not content.strip()) and not tool_calls:
                await report_model_error(route.model, "Empty response")
                raise ValueError(f"Empty response from cloud model {route.model}")
            await report_model_success(route.model)
            return response
        except Exception as e:
            await report_model_error(route.model, str(e)[:500])
            logger.warning("Cloud model %s failed: %s — trying next model", route.model, e)
            # Try next cloud model (skip the failed one)
            fallback = await route_request(
                capability="chat",
                max_tier=max_tier,
                estimated_tokens=context_tokens,
                processing_mode=processing_mode,
                skip_models=[route.model],
            )
            if fallback.target == "openrouter" and fallback.model:
                return await llm_provider.completion(
                    messages=messages,
                    tier=ModelTier.CLOUD_OPENROUTER,
                    max_tokens=max_tokens,
                    temperature=temperature,
                    tools=tools,
                    model_override=fallback.model,
                    api_key_override=fallback.api_key,
                )
            # No more cloud models — fall through to local GPU
            logger.warning("No cloud fallback available, trying local GPU")

    # Route decision: local GPU (GPU is free + context ≤48k, or NONE tier)
    headers = priority_headers(state)
    local_tier = ModelTier.LOCAL_STANDARD

    # W-14: Context overflow guard — ensure messages fit selected tier
    tier_config = TIER_CONFIG.get(local_tier, {})
    tier_ctx_limit = tier_config.get("num_ctx", DEFAULT_TIER_CONTEXT)
    if context_tokens > tier_ctx_limit:
        logger.warning(
            "CONTEXT_OVERFLOW | estimated=%d tokens > tier %s limit=%d | truncating messages",
            context_tokens, local_tier.value, tier_ctx_limit,
        )
        messages = _truncate_messages_to_budget(messages, tier_ctx_limit - max_tokens)

    try:
        response = await llm_provider.completion(
            messages=messages, tier=local_tier,
            max_tokens=max_tokens, temperature=temperature, tools=tools,
            extra_headers=headers,
            model_override=route.model if route.target == "local" else None,
            api_base_override=route.api_base if route.target == "local" else None,
        )
        message = response.choices[0].message
        content = message.content
        tool_calls = getattr(message, "tool_calls", None)

        if (not content or not content.strip()) and not tool_calls:
            raise ValueError("Empty response from local model")
        return response
    except Exception as e:
        logger.warning("Local LLM failed (tier=%s): %s", local_tier.value, e)
        logger.debug("Local LLM exception details:", exc_info=True)
        return await _escalate_to_cloud(
            task, auto, context_tokens, task_type,
            messages, max_tokens, temperature, tools,
            reason=f"Lokální model selhal: {str(e)[:200]}",
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

    # 1. Try auto-enabled providers
    auto_tier = _suggest_cloud_tier(context_tokens, auto_providers_set, task_type)
    if auto_tier:
        logger.info("Auto-escalating to %s (auto_providers=%s)", auto_tier.value, auto_providers_set)
        return await llm_provider.completion(
            messages=messages, tier=auto_tier,
            max_tokens=max_tokens, temperature=temperature, tools=tools,
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

    Only two agents: Claude CLI (default for everything) and Kilo (placeholder).
    """
    if preference and preference != "auto":
        try:
            return AgentType(preference)
        except ValueError:
            logger.warning("Unknown agent preference %r, falling back to Claude", preference)
            return AgentType.CLAUDE

    # Claude handles all complexities
    return AgentType.CLAUDE
