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


async def load_runtime_context(
    query: str = "",
    client_id: str = "",
    project_id: str | None = None,
    group_id: str | None = None,
) -> RuntimeContext:
    """Load runtime data for system prompt enrichment.

    Clients/projects are cached (TTL 5min), pending tasks and meetings are always fresh.
    If query/client_id provided, also loads proactive Thought Map context.
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

    # Proactive dual context: Thought Map + RAG prefetch (parallel)
    thought_context = ""
    rag_context = ""
    activated_thought_ids: list[str] = []
    activated_edge_ids: list[str] = []
    if query and client_id:
        import asyncio

        async def _thought_prefetch():
            from app.kb.thought_prefetch import prefetch_thought_context
            return await prefetch_thought_context(
                query=query, client_id=client_id,
                project_id=project_id, group_id=group_id,
            )

        async def _rag_prefetch():
            return await _prefetch_rag_context(
                query=query, client_id=client_id,
                project_id=project_id, group_id=group_id,
            )

        thought_task = asyncio.create_task(_thought_prefetch())
        rag_task = asyncio.create_task(_rag_prefetch())

        try:
            tc = await thought_task
            thought_context = tc.formatted_context
            activated_thought_ids = tc.activated_thought_ids
            activated_edge_ids = tc.activated_edge_ids
        except Exception as e:
            logger.warning("Failed to load thought context: %s", e)

        try:
            rag_context = await rag_task
        except Exception as e:
            logger.warning("Failed to load RAG context: %s", e)

    return RuntimeContext(
        clients_projects=_clients_cache,
        pending_user_tasks=pending,
        unclassified_meetings_count=unclassified,
        learned_procedures=learned_procedures,
        guidelines_text=guidelines_text,
        thought_context=thought_context,
        rag_context=rag_context,
        activated_thought_ids=activated_thought_ids,
        activated_edge_ids=activated_edge_ids,
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


async def _prefetch_rag_context(
    query: str, client_id: str, project_id: str | None = None, group_id: str | None = None,
) -> str:
    """Proactive RAG search — cosine similarity on KB chunks before LLM call.

    Runs in parallel with Thought Map prefetch. Results injected into system prompt
    so the model ALWAYS sees relevant KB data without needing to call kb_search tool.

    minConfidence lowered from 0.5 → 0.35: the old threshold silently dropped vague /
    conversational queries ("co doučování?") where cosine similarity to any single
    record is below 0.5, leaving the LLM with only Thought Map context →
    hallucinated answers. 0.35 keeps moderate-confidence RAG hits in the running
    while still excluding true noise.
    """
    from jervis_contracts import kb_client

    async def _fetch(cid: str, pid: str, gid: str) -> list[dict]:
        try:
            return await kb_client.retrieve(
                caller="orchestrator.chat.handler_context",
                query=query,
                client_id=cid,
                project_id=pid,
                group_id=gid,
                max_results=8,
                min_confidence=0.35,
                expand_graph=False,  # Pure RAG
                timeout=10.0,
            )
        except Exception as _e:
            logger.debug("RAG_PREFETCH: fetch error (cid=%s pid=%s): %s", cid, pid, _e)
            return []

    results: list[dict] = await _fetch(client_id, project_id or "", group_id or "")

    # Fallback 1: project-scoped miss → retry with client-only scope.
    top_score = max((r.get("score", 0) for r in results), default=0)
    if (not results or top_score < 0.5) and project_id and client_id:
        logger.info("RAG_PREFETCH: project-scoped returned %d results (top=%.2f), trying client-scope fallback...",
                    len(results), top_score)
        fallback_results = await _fetch(client_id, "", "")
        if fallback_results:
            seen = {r.get("sourceUrn", "") for r in results}
            for fr in fallback_results:
                src = fr.get("sourceUrn", "")
                if src and src not in seen:
                    results.append(fr)
                    seen.add(src)
            results.sort(key=lambda x: x.get("score", 0), reverse=True)
            results = results[:8]
            logger.info("RAG_PREFETCH: client-scope fallback added %d results, total=%d",
                        len(fallback_results), len(results))

    # Fallback 2: cross-client global scope. Personal data may live under
    # a different client than the current chat; without this, single-client
    # misses left only Thought Map context (source of earlier hallucinated
    # "thought-anchor:xxx" citations).
    top_score = max((r.get("score", 0) for r in results), default=0)
    if (not results or top_score < 0.45) and client_id:
        logger.info("RAG_PREFETCH: client-scope returned top=%.2f, trying cross-client fallback...",
                    top_score)
        global_results = await _fetch("", "", "")
        if global_results:
            seen = {r.get("sourceUrn", "") for r in results}
            for gr in global_results:
                src = gr.get("sourceUrn", "")
                if src and src not in seen:
                    # Down-weight cross-client hits so same-client results stay on top.
                    gr["score"] = gr.get("score", 0) * 0.85
                    results.append(gr)
                    seen.add(src)
            results.sort(key=lambda x: x.get("score", 0), reverse=True)
            results = results[:8]
            logger.info("RAG_PREFETCH: cross-client fallback added %d results, total=%d",
                        len(global_results), len(results))

    if not results:
        return ""

    # Format as structured context — max ~3000 chars
    parts = []
    total_chars = 0
    for item in results:
        source = item.get("sourceUrn", "?")[:60]
        content = item.get("content", "")
        score = item.get("score", 0)
        if not content:
            continue
        # Truncate per-item to keep total under budget
        snippet = content[:600] if len(content) > 600 else content
        entry = f"[{score:.2f}] {source}: {snippet}"
        if total_chars + len(entry) > 3000:
            break
        parts.append(entry)
        total_chars += len(entry)

    if not parts:
        return ""

    logger.info("RAG_PREFETCH: client=%s results=%d context_len=%d", client_id, len(parts), total_chars)
    return "\n".join(parts)


async def _index_attachment_to_kb(
    filename: str, text: str, mime_type: str = "",
    client_id: str = None, project_id: str = None,
    session_id: str = None,
) -> None:
    """Fire-and-forget: index extracted attachment text into KB for graph/map integration."""
    from datetime import datetime, timezone
    from jervis_contracts import kb_client

    now_iso = datetime.now(timezone.utc).isoformat()
    try:
        await kb_client.ingest(
            caller="orchestrator.chat.attachment_indexer",
            source_urn=f"chat-attachment:{filename}:{now_iso[:19]}",
            content=f"# Příloha z chatu: {filename}\n\n{text}",
            client_id=client_id or "",
            project_id=project_id or "",
            kind="document",
            metadata={
                "filename": filename,
                "mimeType": mime_type,
                "source": "chat_attachment",
                "sessionId": session_id or "",
                "indexedAt": now_iso,
            },
            queue=True,
            timeout=30.0,
        )
        logger.info("Attachment indexed to KB: %s (%d chars, client=%s)", filename, len(text), client_id)
    except Exception as e:
        logger.warning("Attachment KB indexation failed: %s: %s", filename, e)


async def _extract_document_text(file_bytes: bytes, filename: str, mime_type: str = "") -> str:
    """Extract text from any document via Document Extraction Service.

    Routes to the dedicated microservice which handles PDF, DOCX, XLSX,
    images (VLM), HTML, etc. Returns plain text.
    """
    import httpx
    import base64
    from app.config import settings

    docext_url = getattr(settings, "document_extraction_url", None) or "http://jervis-document-extraction:8080"

    try:
        b64 = base64.b64encode(file_bytes).decode("ascii")
        async with httpx.AsyncClient(timeout=120.0) as client:
            resp = await client.post(
                f"{docext_url}/extract-base64",
                data={
                    "content_base64": b64,
                    "filename": filename,
                    "mime_type": mime_type,
                    "max_tier": "NONE",
                },
            )
            if resp.status_code == 200:
                data = resp.json()
                text = data.get("text", "")
                logger.info("Document extracted: %s → %d chars (method=%s)", filename, len(text), data.get("method", "?"))
                return text
            else:
                logger.warning("Document extraction failed: %s → HTTP %d: %s", filename, resp.status_code, resp.text[:200])
    except Exception as e:
        logger.warning("Document extraction error: %s → %s", filename, e)

    return ""


async def build_messages(
    system_prompt: str,
    context,
    task_context_msg: dict | None,
    current_message: str,
    attachments: list[dict] | None = None,
    client_id: str | None = None,
    project_id: str | None = None,
) -> list[dict]:
    """Build LLM messages from context + current message.

    Order:
    1. System prompt
    2. Summaries + memory from AssembledContext
    3. Task context if responding to user_task
    4. Current user message (with inline attachment content)
    """
    messages = [{"role": "system", "content": system_prompt}]

    # Defensive dedup: if the last context message is identical to the current
    # user message, skip it. This guards against the Kotlin-saves-then-Python-loads
    # race where the current user message could otherwise appear twice.
    ctx_msgs = list(context.messages)
    if ctx_msgs:
        last = ctx_msgs[-1]
        last_content = last.get("content") if isinstance(last, dict) else None
        if (
            isinstance(last_content, str)
            and last.get("role") == "user"
            and last_content.strip() == (current_message or "").strip()
        ):
            ctx_msgs = ctx_msgs[:-1]
            logger.debug("build_messages: skipped duplicated current user message from context")

    for msg in ctx_msgs:
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
                # Document → extract text via Document Extraction Service
                import base64
                try:
                    raw = base64.b64decode(b64)
                    # Plain text files → direct decode, everything else → extraction service
                    is_plain_text = mime.startswith("text/") or filename.lower().endswith((".txt", ".csv", ".json", ".xml", ".yaml", ".yml", ".md"))
                    if is_plain_text:
                        text = raw.decode("utf-8", errors="replace")
                    else:
                        text = await _extract_document_text(raw, filename, mime)
                    if text and text.strip():
                        attachment_parts.append(f"\n\n--- Příloha: {filename} ---\n{text}")
                        att["_extracted_text"] = text  # for KB indexation
                    else:
                        attachment_parts.append(f"\n\n--- Příloha: {filename} (prázdný obsah) ---")
                except Exception as e:
                    logger.warning("Attachment extract failed: %s: %s", filename, e)
                    attachment_parts.append(f"\n\n--- Příloha: {filename} (nepodařilo se extrahovat) ---")
            elif b64 and mime.startswith("image/"):
                attachment_parts.append(f"\n\n--- Obrázek: {filename} (vizuální obsah bude zpracován) ---")
            else:
                attachment_parts.append(f"\n\n--- Příloha: {filename} ({mime}) ---")
        user_content += "".join(attachment_parts)

        # Fire-and-forget: index all extracted attachments into KB
        import asyncio
        for att in attachments:
            att_filename = att.get("filename", "attachment")
            att_text = att.get("_extracted_text")
            if att_text and att_text.strip():
                asyncio.create_task(_index_attachment_to_kb(
                    att_filename, att_text,
                    client_id=client_id,
                    project_id=project_id,
                ))

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
