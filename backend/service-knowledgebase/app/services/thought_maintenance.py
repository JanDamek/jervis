"""
Thought Map maintenance — decay, merge, archive, hierarchy.

Two regimes:
  - Light: decay activations + merge similar (safe during chat)
  - Heavy: light + archive dead + Louvain community detection + hierarchy build
"""

import asyncio
import json
import logging
from datetime import datetime, timezone, timedelta

from app.core.config import settings

logger = logging.getLogger(__name__)

# Maintenance config (from config.py or defaults)
DECAY_FACTOR = getattr(settings, "THOUGHT_DECAY_FACTOR", 0.995)
MERGE_THRESHOLD = getattr(settings, "THOUGHT_MERGE_THRESHOLD", 0.92)
ARCHIVE_THRESHOLD = getattr(settings, "THOUGHT_ARCHIVE_THRESHOLD", 0.05)
ARCHIVE_DAYS = getattr(settings, "THOUGHT_ARCHIVE_DAYS", 30)


async def run_light_maintenance(thought_service, client_id: str) -> dict:
    """Light maintenance: decay + merge. Safe to run during active chat."""
    stats = {"decayed": 0, "merged": 0}

    # 1. Decay all activations
    aql_decay = """
        FOR t IN ThoughtNodes
            FILTER t.clientId == @cid
            FILTER t.activationScore > 0
            UPDATE t WITH {
                activationScore: t.activationScore * @factor
            } IN ThoughtNodes
            RETURN 1
    """
    result = await asyncio.to_thread(
        thought_service.db.aql.execute, aql_decay,
        bind_vars={"cid": client_id, "factor": DECAY_FACTOR},
    )
    stats["decayed"] = len(list(result))

    # 2. Merge similar thoughts (cosine > threshold)
    # Find all thought embeddings for client
    aql_all = """
        FOR t IN ThoughtNodes
            FILTER t.clientId == @cid
            FILTER t.embedding != null AND LENGTH(t.embedding) > 0
            RETURN { _key: t._key, _id: t._id, label: t.label, summary: t.summary,
                     embedding: t.embedding, activationScore: t.activationScore }
    """
    all_thoughts = list(await asyncio.to_thread(
        thought_service.db.aql.execute, aql_all,
        bind_vars={"cid": client_id},
    ))

    if len(all_thoughts) < 2:
        logger.info("THOUGHT_MAINTENANCE: light client=%s decayed=%d merged=0 (too few nodes)",
                     client_id, stats["decayed"])
        return stats

    # Brute-force pairwise cosine for merge candidates
    merge_pairs = []
    for i in range(len(all_thoughts)):
        for j in range(i + 1, len(all_thoughts)):
            sim = _cosine_similarity(all_thoughts[i]["embedding"], all_thoughts[j]["embedding"])
            if sim >= MERGE_THRESHOLD:
                merge_pairs.append((all_thoughts[i], all_thoughts[j], sim))

    # Merge: keep the one with higher activation, delete the other, redirect edges
    merged_keys = set()
    for node_a, node_b, sim in sorted(merge_pairs, key=lambda x: -x[2]):
        if node_a["_key"] in merged_keys or node_b["_key"] in merged_keys:
            continue

        # Keep the one with higher activation
        if node_a["activationScore"] >= node_b["activationScore"]:
            keep, remove = node_a, node_b
        else:
            keep, remove = node_b, node_a

        # Redirect edges from removed to kept
        await _redirect_edges(thought_service.db, remove["_id"], keep["_id"])

        # Delete removed node
        try:
            await asyncio.to_thread(thought_service.db.collection("ThoughtNodes").delete, remove["_key"])
            merged_keys.add(remove["_key"])
            stats["merged"] += 1
        except Exception as e:
            logger.warning("THOUGHT_MERGE: failed to delete %s: %s", remove["_key"], e)

    logger.info("THOUGHT_MAINTENANCE: light client=%s decayed=%d merged=%d",
                 client_id, stats["decayed"], stats["merged"])
    return stats


async def run_heavy_maintenance(thought_service, client_id: str) -> dict:
    """Heavy maintenance: light + archive + community detection."""
    # Run light first
    stats = await run_light_maintenance(thought_service, client_id)
    stats["archived"] = 0
    stats["communities"] = 0

    # 3. Archive dead thoughts
    cutoff = (datetime.now(timezone.utc) - timedelta(days=ARCHIVE_DAYS)).isoformat()
    aql_dead = """
        FOR t IN ThoughtNodes
            FILTER t.clientId == @cid
            FILTER t.activationScore < @threshold
            FILTER t.lastActivatedAt < @cutoff
            RETURN t
    """
    dead_thoughts = list(await asyncio.to_thread(
        thought_service.db.aql.execute, aql_dead,
        bind_vars={"cid": client_id, "threshold": ARCHIVE_THRESHOLD, "cutoff": cutoff},
    ))

    for node in dead_thoughts:
        # Archive summary to RAG (Weaviate) as permanent memory
        try:
            from app.api.models import IngestRequest
            archive_content = f"[Archived thought] {node.get('label', '')}: {node.get('summary', '')}"
            archive_request = IngestRequest(
                clientId=client_id,
                sourceUrn=f"thought://archived/{node['_key']}",
                kind="archived_thought",
                content=archive_content,
            )
            await thought_service.rag_service.ingest(archive_request, embedding_priority=4)
        except Exception as e:
            logger.warning("THOUGHT_ARCHIVE: failed to archive %s to RAG: %s", node["_key"], e)
            continue

        # Delete thought node (edges cascade via AQL cleanup)
        try:
            # Delete connected edges first
            await _delete_node_edges(thought_service.db, node["_id"])
            await asyncio.to_thread(thought_service.db.collection("ThoughtNodes").delete, node["_key"])
            stats["archived"] += 1
        except Exception as e:
            logger.warning("THOUGHT_ARCHIVE: failed to delete %s: %s", node["_key"], e)

    # 4. Community detection (Louvain) — build hierarchy
    try:
        stats["communities"] = await _build_hierarchy(thought_service, client_id)
    except Exception as e:
        logger.warning("THOUGHT_HIERARCHY: failed for client=%s: %s", client_id, e)

    logger.info(
        "THOUGHT_MAINTENANCE: heavy client=%s decayed=%d merged=%d archived=%d communities=%d",
        client_id, stats["decayed"], stats["merged"], stats["archived"], stats["communities"],
    )
    return stats


def _cosine_similarity(a: list[float], b: list[float]) -> float:
    """Compute cosine similarity between two vectors."""
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = sum(x * x for x in a) ** 0.5
    norm_b = sum(x * x for x in b) ** 0.5
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return dot / (norm_a * norm_b)


async def _redirect_edges(db, old_id: str, new_id: str):
    """Redirect all ThoughtEdges and ThoughtAnchors from old node to new node."""
    for collection in ["ThoughtEdges", "ThoughtAnchors"]:
        # Redirect _from
        aql = f"""
            FOR e IN {collection}
                FILTER e._from == @oldId
                UPDATE e WITH {{ _from: @newId }} IN {collection}
        """
        await asyncio.to_thread(db.aql.execute, aql, bind_vars={"oldId": old_id, "newId": new_id})

        # Redirect _to (only ThoughtEdges, not anchors which point to KnowledgeNodes)
        if collection == "ThoughtEdges":
            aql = f"""
                FOR e IN {collection}
                    FILTER e._to == @oldId
                    UPDATE e WITH {{ _to: @newId }} IN {collection}
            """
            await asyncio.to_thread(db.aql.execute, aql, bind_vars={"oldId": old_id, "newId": new_id})


async def _delete_node_edges(db, node_id: str):
    """Delete all edges connected to a ThoughtNode."""
    for collection in ["ThoughtEdges", "ThoughtAnchors"]:
        aql = f"""
            FOR e IN {collection}
                FILTER e._from == @nodeId OR e._to == @nodeId
                REMOVE e IN {collection}
        """
        await asyncio.to_thread(db.aql.execute, aql, bind_vars={"nodeId": node_id})


async def _build_hierarchy(thought_service, client_id: str) -> int:
    """Build thought hierarchy using community detection.

    Uses networkx Louvain if available, otherwise skips.
    Returns number of communities found.
    """
    try:
        import networkx as nx
        from networkx.algorithms.community import louvain_communities
    except ImportError:
        logger.info("THOUGHT_HIERARCHY: networkx not available, skipping community detection")
        return 0

    # Load graph into networkx
    aql_nodes = """
        FOR t IN ThoughtNodes
            FILTER t.clientId == @cid
            FILTER t.type != 'topic'  // Don't cluster meta-thoughts
            RETURN { key: t._key, label: t.label }
    """
    aql_edges = """
        FOR e IN ThoughtEdges
            FILTER e.clientId == @cid
            RETURN { from: PARSE_IDENTIFIER(e._from).key, to: PARSE_IDENTIFIER(e._to).key, weight: e.weight }
    """
    nodes = list(await asyncio.to_thread(
        thought_service.db.aql.execute, aql_nodes, bind_vars={"cid": client_id}
    ))
    edges = list(await asyncio.to_thread(
        thought_service.db.aql.execute, aql_edges, bind_vars={"cid": client_id}
    ))

    if len(nodes) < 5:
        return 0

    G = nx.Graph()
    for n in nodes:
        G.add_node(n["key"], label=n["label"])
    for e in edges:
        if G.has_node(e["from"]) and G.has_node(e["to"]):
            G.add_edge(e["from"], e["to"], weight=e.get("weight", 0.5))

    if G.number_of_edges() == 0:
        return 0

    # Louvain community detection
    communities = louvain_communities(G, resolution=1.0)
    community_count = 0

    for community in communities:
        if len(community) < 2:
            continue

        # Get labels for community members
        member_labels = [G.nodes[k].get("label", k) for k in community]
        meta_label = f"Cluster: {', '.join(member_labels[:3])}..."
        meta_summary = f"Thematic cluster of {len(community)} related thoughts: {', '.join(member_labels[:5])}"

        # Create meta-thought for community
        await thought_service.upsert_thought(
            label=meta_label,
            summary=meta_summary,
            thought_type="topic",
            client_id=client_id,
            source_type="maintenance",
            embedding_priority=4,
        )
        community_count += 1

    return community_count
