"""Runtime context loading, caching, and LLM message building for chat handler.

Responsibilities:
- Load and cache clients/projects (TTL 5min)
- Load pending user tasks and unclassified meetings
- Load learned procedures from KB
- Build LLM message array from context + current message
- Load task context for user_task responses
"""
from __future__ import annotations

import logging
import time

from app.chat.system_prompt import RuntimeContext
from app.config import settings

logger = logging.getLogger(__name__)

# Runtime context cache (clients-projects)
_clients_cache: list[dict] = []
_clients_cache_at: float = 0

# Learned procedures cache (same TTL)
_procedures_cache: list[str] = []
_procedures_cache_at: float = 0


async def load_runtime_context() -> RuntimeContext:
    """Load runtime data for system prompt enrichment.

    Clients/projects are cached (TTL 5min), pending tasks and meetings are always fresh.
    """
    from app.tools.kotlin_client import kotlin_client

    global _clients_cache, _clients_cache_at

    now = time.monotonic()
    if now - _clients_cache_at > settings.guidelines_cache_ttl or not _clients_cache:
        try:
            _clients_cache = await kotlin_client.get_clients_projects()
            _clients_cache_at = now
        except Exception as e:
            logger.warning("Failed to load clients-projects: %s", e)

    # Pending user tasks — always fresh
    try:
        pending = await kotlin_client.get_pending_user_tasks_summary(limit=3)
    except Exception as e:
        logger.warning("Failed to load pending user tasks: %s", e)
        pending = {"count": 0, "tasks": []}

    # Unclassified meetings — always fresh
    try:
        unclassified = await kotlin_client.count_unclassified_meetings()
    except Exception as e:
        logger.warning("Failed to count unclassified meetings: %s", e)
        unclassified = 0

    # Learned procedures — from KB (cached, 5min TTL)
    learned_procedures: list[str] = []
    try:
        learned_procedures = await _load_learned_procedures()
    except Exception as e:
        logger.warning("Failed to load learned procedures: %s", e)

    # Guidelines — from Kotlin server (cached in resolver, 5min TTL)
    guidelines_text = ""
    try:
        from app.context.guidelines_resolver import resolve_guidelines, format_guidelines_for_prompt
        guidelines_data = await resolve_guidelines()  # Global guidelines for chat
        guidelines_text = format_guidelines_for_prompt(guidelines_data)
    except Exception as e:
        logger.warning("Failed to load guidelines: %s", e)

    return RuntimeContext(
        clients_projects=_clients_cache,
        pending_user_tasks=pending,
        unclassified_meetings_count=unclassified,
        learned_procedures=learned_procedures,
        guidelines_text=guidelines_text,
    )


async def _load_learned_procedures() -> list[str]:
    """Load learned procedures/conventions from KB for system prompt enrichment.

    Searches KB for entries stored via memory_store(category="procedure").
    Cached for 5 minutes to avoid per-message KB search overhead.
    Returns list of procedure strings (max 20).
    """
    from app.tools.executor import execute_tool

    global _procedures_cache, _procedures_cache_at

    now = time.monotonic()
    if now - _procedures_cache_at < settings.guidelines_cache_ttl and _procedures_cache:
        return _procedures_cache

    try:
        result = await execute_tool(
            tool_name="kb_search",
            arguments={"query": "postup konvence pravidlo procedure convention", "max_results": 20},
            client_id="",
            project_id=None,
            processing_mode="FOREGROUND",
        )

        procedures: list[str] = []
        if result and not result.startswith("Error"):
            for line in result.split("\n"):
                line = line.strip()
                if line and 10 < len(line) < 500:
                    if any(kw in line.lower() for kw in ["postup", "konvence", "pravidlo", "procedure", "vždy", "nikdy", "default"]):
                        procedures.append(line)

        _procedures_cache = procedures[:20]
        _procedures_cache_at = now
        if procedures:
            logger.info("Chat: loaded %d learned procedures for system prompt", len(procedures))
        return _procedures_cache

    except Exception as e:
        logger.warning("Failed to load learned procedures from KB: %s", e)
        return _procedures_cache


def build_messages(
    system_prompt: str,
    context,
    task_context_msg: dict | None,
    current_message: str,
    attachments: list[dict] | None = None,
) -> list[dict]:
    """Build LLM messages from context + current message.

    Order:
    1. System prompt
    2. Summaries + memory from AssembledContext
    3. Task context if responding to user_task
    4. Current user message (with inline attachment content)
    """
    messages = [{"role": "system", "content": system_prompt}]

    for msg in context.messages:
        messages.append(msg)

    if task_context_msg:
        messages.append(task_context_msg)

    # Build user message — include attachment text inline
    user_content = current_message
    if attachments:
        attachment_parts = []
        for att in attachments:
            filename = att.get("filename", "attachment")
            mime = att.get("mime_type", "")
            b64 = att.get("content_base64")
            if b64 and not mime.startswith("image/"):
                # Text/document — decode and include inline
                import base64
                try:
                    raw = base64.b64decode(b64)
                    text = raw.decode("utf-8", errors="replace")
                    attachment_parts.append(f"\n\n--- Příloha: {filename} ---\n{text[:8000]}")
                except Exception:
                    attachment_parts.append(f"\n\n--- Příloha: {filename} (nepodařilo se dekódovat) ---")
            elif b64 and mime.startswith("image/"):
                attachment_parts.append(f"\n\n--- Obrázek: {filename} (vizuální obsah bude zpracován) ---")
            else:
                attachment_parts.append(f"\n\n--- Příloha: {filename} ({mime}) ---")
        user_content += "".join(attachment_parts)

    # For image attachments, use multimodal content format
    image_parts = []
    if attachments:
        for att in attachments:
            mime = att.get("mime_type", "")
            b64 = att.get("content_base64")
            if b64 and mime.startswith("image/"):
                image_parts.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:{mime};base64,{b64}"},
                })

    if image_parts:
        # Multimodal message (text + images)
        content_parts = [{"type": "text", "text": user_content}] + image_parts
        messages.append({"role": "user", "content": content_parts})
    else:
        messages.append({"role": "user", "content": user_content})

    return messages


async def load_task_context_message(task_id: str) -> dict | None:
    """Load task context for user_task response."""
    try:
        from app.tools.kotlin_client import kotlin_client
        task_data = await kotlin_client.get_user_task(task_id)
        if not task_data:
            return None

        return {
            "role": "system",
            "content": (
                f"[Kontext user_task {task_id}]\n"
                f"Název: {task_data.get('title', 'N/A')}\n"
                f"Otázka: {task_data.get('question', 'N/A')}\n"
                f"Dosavadní kontext:\n{task_data.get('context', 'N/A')}\n"
                f"\nUser na tuto otázku odpovídá v následující zprávě. "
                f"Po zpracování odpovědi zavolej respond_to_user_task."
            ),
        }
    except Exception as e:
        logger.warning("Failed to load task context for %s: %s", task_id, e)
        return None
