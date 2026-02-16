"""Affair lifecycle management — create, park, resume, resolve.

Affairs are thematic containers that group contextually-related
information under a single topic. This module handles their
lifecycle transitions and KB persistence.
"""

from __future__ import annotations

import json
import logging
import uuid
from datetime import datetime, timezone

import httpx

from app.memory.lqm import LocalQuickMemory
from app.memory.models import (
    Affair,
    AffairMessage,
    AffairStatus,
    PendingWrite,
    WritePriority,
)

logger = logging.getLogger(__name__)


async def create_affair(
    client_id: str,
    project_id: str | None,
    title: str,
    initial_context: str,
    lqm: LocalQuickMemory,
) -> Affair:
    """Create a new affair and store in LQM."""
    now = datetime.now(timezone.utc).isoformat()
    affair = Affair(
        id=str(uuid.uuid4()),
        title=title,
        summary="",
        status=AffairStatus.ACTIVE,
        topics=[],
        created_at=now,
        updated_at=now,
        client_id=client_id,
        project_id=project_id,
        messages=[
            AffairMessage(role="user", content=initial_context[:2000], timestamp=now),
        ],
    )
    lqm.store_affair(affair)
    logger.info("Created affair: %s (%s)", affair.title, affair.id)
    return affair


async def park_affair(
    affair: Affair,
    state: dict,
    lqm: LocalQuickMemory,
) -> None:
    """Park the current affair with LLM-generated summary.

    1. LLM summarizes the affair (summary, key_facts, pending_actions)
    2. Updates affair status to PARKED in LQM
    3. Queues CRITICAL KB write
    """
    # Generate summary via LLM
    try:
        summary_result = await _summarize_affair_for_parking(affair, state)
        affair.summary = summary_result.get("summary", affair.summary)
        if summary_result.get("key_facts"):
            affair.key_facts.update(summary_result["key_facts"])
        if summary_result.get("pending_actions"):
            affair.pending_actions = summary_result["pending_actions"]
        if summary_result.get("topics"):
            affair.topics = list(set(affair.topics + summary_result["topics"]))
    except Exception as e:
        logger.warning("Affair summarization failed (non-blocking): %s", e)
        # Still park — just without updated summary

    affair.status = AffairStatus.PARKED
    affair.updated_at = datetime.now(timezone.utc).isoformat()

    # Store in LQM (immediately available)
    lqm.store_affair(affair)

    # Queue CRITICAL KB write
    write = PendingWrite(
        source_urn=f"affair:{affair.id}",
        content=affair.to_kb_document(),
        kind="affair",
        metadata={
            "affair_id": affair.id,
            "title": affair.title,
            "status": affair.status.value,
            "topics": ",".join(affair.topics),
            "client_id": affair.client_id,
            "project_id": affair.project_id or "",
        },
        priority=WritePriority.CRITICAL,
        created_at=affair.updated_at,
    )
    await lqm.buffer_write(write)

    logger.info("Parked affair: %s (%s)", affair.title, affair.id)


async def resume_affair(
    affair_id: str,
    lqm: LocalQuickMemory,
    kb_url: str,
    client_id: str,
) -> Affair | None:
    """Resume a parked affair.

    1. Check LQM hot cache (fast path)
    2. Fallback: load from KB via semantic search
    3. Update status to ACTIVE in LQM
    """
    # Fast path: LQM
    affair = lqm.get_affair(affair_id)
    if affair:
        affair.status = AffairStatus.ACTIVE
        affair.updated_at = datetime.now(timezone.utc).isoformat()
        lqm.store_affair(affair)
        logger.info("Resumed affair from LQM: %s", affair.title)
        return affair

    # Fallback: load from KB
    try:
        affair = await _load_affair_from_kb(affair_id, kb_url, client_id)
        if affair:
            affair.status = AffairStatus.ACTIVE
            affair.updated_at = datetime.now(timezone.utc).isoformat()
            lqm.store_affair(affair)
            logger.info("Resumed affair from KB: %s", affair.title)
            return affair
    except Exception as e:
        logger.warning("Failed to load affair from KB: %s", e)

    logger.warning("Affair not found: %s", affair_id)
    return None


async def resolve_affair(
    affair_id: str,
    lqm: LocalQuickMemory,
) -> None:
    """Mark affair as RESOLVED and queue KB write."""
    affair = lqm.get_affair(affair_id)
    if not affair:
        logger.warning("Cannot resolve — affair not found: %s", affair_id)
        return

    affair.status = AffairStatus.RESOLVED
    affair.updated_at = datetime.now(timezone.utc).isoformat()
    lqm.store_affair(affair)

    # Queue KB write
    write = PendingWrite(
        source_urn=f"affair:{affair.id}",
        content=affair.to_kb_document(),
        kind="affair",
        metadata={
            "affair_id": affair.id,
            "title": affair.title,
            "status": affair.status.value,
            "topics": ",".join(affair.topics),
            "client_id": affair.client_id,
            "project_id": affair.project_id or "",
        },
        priority=WritePriority.HIGH,
        created_at=affair.updated_at,
    )
    await lqm.buffer_write(write)
    logger.info("Resolved affair: %s (%s)", affair.title, affair.id)


async def load_affairs_from_kb(
    client_id: str,
    project_id: str | None,
    kb_url: str,
) -> list[Affair]:
    """Cold start: load ACTIVE+PARKED affairs from KB.

    Tries dedicated endpoint first, falls back to semantic search.
    """
    # Try dedicated endpoint
    try:
        affairs = await _load_affairs_via_endpoint(client_id, project_id, kb_url)
        if affairs is not None:
            return affairs
    except Exception as e:
        logger.debug("Dedicated affairs endpoint not available: %s", e)

    # Fallback: semantic search with kind=affair
    try:
        return await _load_affairs_via_search(client_id, kb_url)
    except Exception as e:
        logger.warning("Failed to load affairs from KB: %s", e)
        return []


# ---------------------------------------------------------------------------
# Private helpers
# ---------------------------------------------------------------------------


async def _summarize_affair_for_parking(
    affair: Affair,
    state: dict,
) -> dict:
    """LLM-based summarization for affair parking.

    Returns dict with: summary, key_facts, pending_actions, topics.
    """
    # Lazy import to avoid circular dependency
    from app.graph.nodes._helpers import llm_with_cloud_fallback

    # Build context from affair messages
    messages_text = "\n".join(
        f"[{m.role}]: {m.content[:500]}" for m in affair.messages[-10:]
    )

    prompt = f"""Shrň záležitost pro pozdější obnovení kontextu.

ZÁLEŽITOST: {affair.title}
DOSAVADNÍ FAKTA: {json.dumps(affair.key_facts, ensure_ascii=False) if affair.key_facts else "(žádná)"}
POSLEDNÍ ZPRÁVY:
{messages_text}

Odpověz POUZE validním JSON:
{{
    "summary": "2-3 věty shrnující aktuální stav záležitosti",
    "key_facts": {{"klíč": "hodnota", ...}},
    "pending_actions": ["akce 1", "akce 2"],
    "topics": ["téma1", "téma2"]
}}"""

    response = await llm_with_cloud_fallback(
        state=state,
        messages=[
            {"role": "system", "content": "You are a summarization assistant. Respond with valid JSON only."},
            {"role": "user", "content": prompt},
        ],
        context_tokens=len(prompt) // 4,
        task_type="summarization",
        max_tokens=512,
        temperature=0.1,
    )

    content = response.choices[0].message.content or ""

    # Strip markdown fences
    text = content.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[-1]
        if text.endswith("```"):
            text = text[:-3]
        text = text.strip()

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        logger.warning("Failed to parse parking summary JSON, using raw content")
        return {"summary": content[:500]}


async def _load_affair_from_kb(
    affair_id: str,
    kb_url: str,
    client_id: str,
) -> Affair | None:
    """Load a single affair from KB by its source_urn."""
    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post(
            f"{kb_url}/api/v1/retrieve",
            json={
                "query": f"affair:{affair_id}",
                "clientId": client_id,
                "kinds": ["affair"],
                "maxResults": 1,
            },
            headers={"X-Ollama-Priority": "1"},
        )
        if resp.status_code != 200:
            return None

        chunks = resp.json().get("chunks", [])
        if not chunks:
            return None

        return _chunk_to_affair(chunks[0], client_id)


async def _load_affairs_via_endpoint(
    client_id: str,
    project_id: str | None,
    kb_url: str,
) -> list[Affair] | None:
    """Try dedicated /api/v1/affairs endpoint."""
    params = {"client_id": client_id, "status": "ACTIVE,PARKED"}
    if project_id:
        params["project_id"] = project_id

    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.get(f"{kb_url}/api/v1/affairs", params=params)
        if resp.status_code == 404:
            return None  # Endpoint not yet deployed
        if resp.status_code != 200:
            return None

        data = resp.json()
        return [Affair(**a) for a in data.get("affairs", [])]


async def _load_affairs_via_search(
    client_id: str,
    kb_url: str,
) -> list[Affair]:
    """Fallback: load affairs via semantic search with kind=affair."""
    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post(
            f"{kb_url}/api/v1/retrieve",
            json={
                "query": "active parked affairs",
                "clientId": client_id,
                "kinds": ["affair"],
                "maxResults": 20,
            },
            headers={"X-Ollama-Priority": "1"},
        )
        if resp.status_code != 200:
            return []

        chunks = resp.json().get("chunks", [])
        affairs = []
        seen_ids = set()
        for chunk in chunks:
            affair = _chunk_to_affair(chunk, client_id)
            if affair and affair.id not in seen_ids:
                seen_ids.add(affair.id)
                affairs.append(affair)

        return affairs


def _chunk_to_affair(chunk: dict, client_id: str) -> Affair | None:
    """Convert a KB chunk to an Affair model."""
    try:
        metadata = chunk.get("metadata", {})
        affair_id = metadata.get("affair_id", str(uuid.uuid4()))
        status_str = metadata.get("status", "parked").lower()
        status = AffairStatus(status_str) if status_str in AffairStatus.__members__.values() else AffairStatus.PARKED

        topics_str = metadata.get("topics", "")
        topics = [t.strip() for t in topics_str.split(",") if t.strip()] if topics_str else []

        return Affair(
            id=affair_id,
            title=metadata.get("title", "Unknown"),
            summary=chunk.get("content", "")[:1000],
            status=status,
            topics=topics,
            client_id=client_id,
            project_id=metadata.get("project_id") or None,
            created_at=metadata.get("created_at", ""),
            updated_at=metadata.get("updated_at", ""),
        )
    except Exception as e:
        logger.warning("Failed to parse affair from KB chunk: %s", e)
        return None
