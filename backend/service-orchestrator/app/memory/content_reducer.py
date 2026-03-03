"""Content reduction via LLM — replaces blind truncation.

CRITICAL RULE (from spec):
    NIKDY neořezávat zprávy (pre-trim). Veškerý obsah musí být zpracován.

Three functions:
  reduce_for_prompt()          — async LLM reduction for prompt construction
  reduce_messages_for_prompt() — async batch reduction for message lists
  trim_for_display()           — sync truncation for display/logging ONLY

When content exceeds the token budget of the current model:
1. LLM creates a structured summary preserving ALL key information
2. Original is stored in KB for retrieval
3. If LLM reduction fails → return full content (caller decides, NEVER truncate)
"""

from __future__ import annotations

import logging

from app.config import estimate_tokens

logger = logging.getLogger(__name__)

# Below this threshold the LLM reduction prompt overhead dominates — skip
_MIN_REDUCTION_BUDGET_TOKENS = 80

# Maximum content tokens for single-pass LOCAL_COMPACT reduction (~32k model)
_SINGLE_PASS_LIMIT_TOKENS = 24_000

# Prompt overhead for the reduction system/user wrapper
_REDUCTION_PROMPT_OVERHEAD_TOKENS = 120


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


async def reduce_for_prompt(
    content: str,
    token_budget: int,
    purpose: str = "general",
    *,
    state: dict | None = None,
) -> str:
    """Reduce content to fit *token_budget* via LLM summarization.

    Fast path: returns content unchanged if it already fits.
    LLM path: structured summary preserving ALL key information.
    Fallback: returns full content (NEVER truncates).

    Args:
        content:      The text to potentially reduce.
        token_budget: Maximum tokens for the reduced version.
        purpose:      Hint for reduction style
                      ("summary", "key_facts", "message_summary", "context").
        state:        Optional orchestrator state dict.
                      When provided, ``llm_with_cloud_fallback`` is used which
                      handles cloud escalation (including Gemini for very large
                      content) based on project rules.  Without state the call
                      goes to LOCAL_COMPACT only.
    """
    if not content:
        return content

    current_tokens = estimate_tokens(content)
    if current_tokens <= token_budget:
        return content

    if token_budget < _MIN_REDUCTION_BUDGET_TOKENS:
        logger.debug("Token budget too small for LLM reduction (%d), returning full", token_budget)
        return content

    try:
        if current_tokens > _SINGLE_PASS_LIMIT_TOKENS:
            return await _multi_pass_reduce(content, token_budget, purpose, state)
        return await _single_pass_reduce(content, token_budget, purpose, state)
    except Exception as e:
        logger.warning("Content reduction failed: %s — returning full content", e)
        return content  # NEVER truncate


async def reduce_messages_for_prompt(
    messages: list,
    token_budget: int,
    msg_format: str = "[{role}]: {content}",
    *,
    state: dict | None = None,
) -> str:
    """Build a text block from *messages* fitting within *token_budget*.

    Processes messages **newest-first**; if a single message exceeds the
    remaining budget it is reduced via LLM (not truncated).  Stops when
    the budget is exhausted.
    """
    if not messages:
        return ""

    lines: list[str] = []
    remaining = token_budget

    for msg in reversed(messages):
        role = msg.role if hasattr(msg, "role") else msg.get("role", "user")
        content = msg.content if hasattr(msg, "content") else msg.get("content", "")

        content_tokens = estimate_tokens(content)

        if content_tokens > remaining - _REDUCTION_PROMPT_OVERHEAD_TOKENS:
            usable = remaining - _REDUCTION_PROMPT_OVERHEAD_TOKENS
            if usable > _MIN_REDUCTION_BUDGET_TOKENS:
                content = await reduce_for_prompt(
                    content, usable, "message_summary", state=state,
                )
                content_tokens = estimate_tokens(content)
            else:
                break  # no budget left

        line = msg_format.format(role=role, content=content)
        line_tokens = estimate_tokens(line)

        if line_tokens > remaining:
            break

        lines.insert(0, line)
        remaining -= line_tokens

    return "\n".join(lines)


def trim_for_display(content: str, max_chars: int) -> str:
    """Truncate for display / logging purposes ONLY.

    NOT for data processing or storage.  Acceptable uses:
    - Error messages in logs
    - UI progress indicators
    - Debug logging

    Adds ``[…]`` marker when truncated so the reader knows.
    """
    if not content or len(content) <= max_chars:
        return content
    return content[: max_chars] + " [\u2026]"


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


async def _single_pass_reduce(
    content: str,
    token_budget: int,
    purpose: str,
    state: dict | None,
) -> str:
    """Reduce via a single LLM call (content fits model context)."""
    target_chars = token_budget * 4  # approximate chars-per-token
    prompt = _build_reduction_prompt(content, target_chars, purpose)
    prompt_tokens = estimate_tokens(prompt) + _REDUCTION_PROMPT_OVERHEAD_TOKENS

    if state is not None:
        # Use cloud-fallback path (handles Gemini for large context)
        from app.graph.nodes._helpers import llm_with_cloud_fallback

        response = await llm_with_cloud_fallback(
            state=state,
            messages=[
                {"role": "system", "content": _SYSTEM_INSTRUCTION},
                {"role": "user", "content": prompt},
            ],
            context_tokens=prompt_tokens,
            task_type="summarization",
            max_tokens=min(token_budget + 100, 4096),
            temperature=0.1,
        )
    else:
        # Local-only path (no project rules available)
        from app.chat.handler_streaming import call_llm
        from app.models import ModelTier

        response = await call_llm(
            messages=[
                {"role": "system", "content": _SYSTEM_INSTRUCTION},
                {"role": "user", "content": prompt},
            ],
            tier=ModelTier.LOCAL_COMPACT,
            max_tokens=min(token_budget + 100, 4096),
            temperature=0.1,
            timeout=15.0,
        )

    reduced = response.choices[0].message.content or ""
    reduced_tokens = estimate_tokens(reduced)

    # 10 % tolerance — if LLM slightly overshot, still accept
    if reduced and reduced_tokens <= int(token_budget * 1.1):
        return reduced

    logger.warning(
        "LLM reduction overshot budget (%d > %d), returning full content",
        reduced_tokens, token_budget,
    )
    return content  # NEVER truncate


async def _multi_pass_reduce(
    content: str,
    token_budget: int,
    purpose: str,
    state: dict | None,
) -> str:
    """Reduce very large content via chunked multi-pass.

    1. Split into chunks that fit LOCAL_COMPACT context.
    2. Reduce each chunk independently.
    3. Merge reduced chunks; if still over budget, final reduction pass.
    """
    chunk_chars = _SINGLE_PASS_LIMIT_TOKENS * 4  # approximate char limit
    chunks = [content[i : i + chunk_chars] for i in range(0, len(content), chunk_chars)]

    per_chunk_budget = max(token_budget // len(chunks), _MIN_REDUCTION_BUDGET_TOKENS)
    reduced_chunks: list[str] = []

    for chunk in chunks:
        reduced = await _single_pass_reduce(chunk, per_chunk_budget, purpose, state)
        reduced_chunks.append(reduced)

    combined = "\n---\n".join(reduced_chunks)

    if estimate_tokens(combined) <= token_budget:
        return combined

    # Final merge pass
    if estimate_tokens(combined) <= _SINGLE_PASS_LIMIT_TOKENS:
        return await _single_pass_reduce(combined, token_budget, "summary", state)

    # Still too large — return best effort (never truncate)
    logger.warning(
        "Multi-pass reduction still over budget (%d > %d), returning best effort",
        estimate_tokens(combined), token_budget,
    )
    return combined


_SYSTEM_INSTRUCTION = (
    "Zredukuj obsah. Zachovej VŠECHNY klíčové informace, fakta, "
    "rozhodnutí a požadavky. Odpověz POUZE zredukovaným textem."
)

_PURPOSE_INSTRUCTIONS = {
    "general": "Zachovej všechny klíčové informace.",
    "key_facts": "Zachovej všechna fakta, čísla, jména a rozhodnutí.",
    "message_summary": "Zachovej hlavní sdělení, požadavky a rozhodnutí.",
    "context": "Zachovej kontext potřebný pro pochopení situace.",
    "summary": "Vytvoř strukturovaný souhrn.",
}


def _build_reduction_prompt(content: str, target_chars: int, purpose: str) -> str:
    instruction = _PURPOSE_INSTRUCTIONS.get(purpose, _PURPOSE_INSTRUCTIONS["general"])
    return (
        f"Zredukuj následující obsah na maximálně {target_chars} znaků.\n"
        f"{instruction}\n\n"
        f"OBSAH:\n{content}"
    )
