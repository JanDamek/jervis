"""
Thought Map — navigation layer over KB graph.

Provides ThoughtNodes (high-level insights/decisions/problems),
ThoughtEdges (relationships between thoughts), and
ThoughtAnchors (connections from thoughts to KnowledgeNodes).

Uses spreading activation for context retrieval instead of flat search.
"""

import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Optional

from arango.database import StandardDatabase

from app.core.config import settings

logger = logging.getLogger(__name__)

# Multi-tenant filter — same pattern as graph_service.py
_TENANT_FILTER = """
    (v.clientId == '' OR v.clientId == null OR v.clientId == @clientId)
    AND (v.projectId == '' OR v.projectId == null OR v.projectId == @projectId OR v.groupId == @groupId)
"""

_TENANT_FILTER_T = """
    (t.clientId == '' OR t.clientId == null OR t.clientId == @clientId)
    AND (t.projectId == '' OR t.projectId == null OR t.projectId == @projectId OR t.groupId == @groupId)
"""


class ThoughtService:
    """Manages ThoughtNodes, ThoughtEdges, and ThoughtAnchors in ArangoDB."""

    def __init__(self, db: StandardDatabase, rag_service):
        self.db = db
        self.rag_service = rag_service
        self._ensure_schema()

    # ── Schema ────────────────────────────────────────────────────────────

    def _ensure_schema(self):
        """Create collections, indexes, and named graph if they don't exist."""
        # Document collection
        if not self.db.has_collection("ThoughtNodes"):
            self.db.create_collection("ThoughtNodes")
            logger.info("Created collection ThoughtNodes")

        # Edge collections
        if not self.db.has_collection("ThoughtEdges"):
            self.db.create_collection("ThoughtEdges", edge=True)
            logger.info("Created collection ThoughtEdges")

        if not self.db.has_collection("ThoughtAnchors"):
            self.db.create_collection("ThoughtAnchors", edge=True)
            logger.info("Created collection ThoughtAnchors")

        # Indexes — ThoughtNodes
        nodes_col = self.db.collection("ThoughtNodes")
        self._ensure_index(nodes_col, "hash", ["clientId"], "idx_thought_nodes_client")
        self._ensure_index(nodes_col, "hash", ["type"], "idx_thought_nodes_type")
        self._ensure_index(nodes_col, "persistent", ["activationScore"], "idx_thought_nodes_activation")
        self._ensure_index(nodes_col, "persistent", ["lastActivatedAt"], "idx_thought_nodes_last_activated")

        # Indexes — ThoughtEdges
        edges_col = self.db.collection("ThoughtEdges")
        self._ensure_index(edges_col, "hash", ["clientId"], "idx_thought_edges_client")
        self._ensure_index(edges_col, "hash", ["edgeType"], "idx_thought_edges_type")

        # Indexes — ThoughtAnchors
        anchors_col = self.db.collection("ThoughtAnchors")
        self._ensure_index(anchors_col, "hash", ["clientId"], "idx_thought_anchors_client")
        self._ensure_index(anchors_col, "hash", ["anchorType"], "idx_thought_anchors_type")

        # Named graph
        if not self.db.has_graph("thought_graph"):
            self.db.create_graph(
                "thought_graph",
                edge_definitions=[
                    {
                        "edge_collection": "ThoughtEdges",
                        "from_vertex_collections": ["ThoughtNodes"],
                        "to_vertex_collections": ["ThoughtNodes"],
                    },
                    {
                        "edge_collection": "ThoughtAnchors",
                        "from_vertex_collections": ["ThoughtNodes"],
                        "to_vertex_collections": ["KnowledgeNodes"],
                    },
                ],
            )
            logger.info("Created named graph thought_graph")

        logger.info("ThoughtService schema ready")

    @staticmethod
    def _ensure_index(collection, index_type: str, fields: list[str], name: str):
        """Create index if it doesn't exist (idempotent)."""
        existing = {idx.get("name") for idx in collection.indexes()}
        if name not in existing:
            collection.add_index({"type": index_type, "fields": fields, "name": name})

    # ── Embedding ─────────────────────────────────────────────────────────

    async def _embed_text(self, text: str, priority: int = 3) -> list[float]:
        """Embed text using the same model as KB (qwen3-embedding:8b)."""
        return await self.rag_service._embed_with_priority(text, priority=priority)

    # ── Find nearest thoughts (cosine brute-force in ArangoDB) ────────────

    async def find_nearest(
        self,
        embedding: list[float],
        client_id: str,
        project_id: str = "",
        group_id: str = "",
        threshold: float = 0.85,
        top_k: int = 5,
    ) -> list[dict]:
        """Find ThoughtNodes closest to embedding via brute-force cosine in ArangoDB."""
        aql = """
            FOR t IN ThoughtNodes
                FILTER (t.clientId == '' OR t.clientId == null OR t.clientId == @clientId)
                AND (t.projectId == '' OR t.projectId == null OR t.projectId == @projectId OR t.groupId == @groupId)
                FILTER t.embedding != null AND LENGTH(t.embedding) > 0
                LET sim = (
                    LET dot = SUM(FOR i IN 0..LENGTH(t.embedding)-1 RETURN t.embedding[i] * @queryEmbedding[i])
                    LET normA = SQRT(SUM(FOR x IN t.embedding RETURN x * x))
                    LET normB = SQRT(SUM(FOR x IN @queryEmbedding RETURN x * x))
                    RETURN normA > 0 AND normB > 0 ? dot / (normA * normB) : 0
                )[0]
                FILTER sim >= @threshold
                SORT sim DESC
                LIMIT @topK
                RETURN { node: t, similarity: sim }
        """
        result = await asyncio.to_thread(
            self.db.aql.execute,
            aql,
            bind_vars={
                "queryEmbedding": embedding,
                "clientId": client_id,
                "projectId": project_id or "",
                "groupId": group_id or "",
                "threshold": threshold,
                "topK": top_k,
            },
        )
        return list(result)

    # ── Spreading activation ──────────────────────────────────────────────

    async def traverse(
        self,
        query: str,
        client_id: str,
        project_id: str = "",
        group_id: str = "",
        max_results: int = 20,
        floor: float = 0.1,
        max_depth: int = 3,
        entry_top_k: int = 5,
    ) -> dict:
        """
        Spreading activation: embed query → find entry thoughts → AQL traversal.

        Returns dict with:
          - thoughts: list of activated ThoughtNodes with pathWeight
          - knowledge: list of anchored KnowledgeNodes
          - activated_thought_ids: list of ThoughtNode _keys (for reinforcement)
          - activated_edge_ids: list of ThoughtEdge _keys (for reinforcement)
        """
        # Step 1: Embed query
        embedding = await self._embed_text(query, priority=1)

        # Step 2: Find entry points (nearest thoughts)
        entry_results = await self.find_nearest(
            embedding=embedding,
            client_id=client_id,
            project_id=project_id,
            group_id=group_id,
            threshold=0.3,  # Lower threshold for entry — spreading activation will filter
            top_k=entry_top_k,
        )

        if not entry_results:
            logger.info("THOUGHT_TRAVERSE: no entry points found for client=%s", client_id)
            return {"thoughts": [], "knowledge": [], "activated_thought_ids": [], "activated_edge_ids": []}

        entry_ids = [r["node"]["_id"] for r in entry_results]

        # Step 3: Spreading activation via AQL
        aql = """
            LET allResults = (
                FOR startNode IN @entryNodes
                    FOR v, e, p IN 1..@maxDepth OUTBOUND startNode
                        ThoughtEdges, ThoughtAnchors
                        FILTER (v.clientId == '' OR v.clientId == null OR v.clientId == @clientId)
                        AND (v.projectId == '' OR v.projectId == null OR v.projectId == @projectId OR v.groupId == @groupId)
                        LET pathWeight = PRODUCT(FOR edge IN p.edges RETURN edge.weight != null ? edge.weight : 0.5)
                        FILTER pathWeight >= @floor
                        RETURN {
                            vertex: v,
                            pathWeight: pathWeight,
                            depth: LENGTH(p.edges),
                            collection: PARSE_IDENTIFIER(v._id).collection,
                            edgeKeys: (FOR edge IN p.edges RETURN edge._key)
                        }
            )
            FOR r IN allResults
                SORT r.pathWeight DESC
                LIMIT @maxResults
                RETURN r
        """
        traversal_results = await asyncio.to_thread(
            self.db.aql.execute,
            aql,
            bind_vars={
                "entryNodes": entry_ids,
                "maxDepth": max_depth,
                "clientId": client_id,
                "projectId": project_id or "",
                "groupId": group_id or "",
                "floor": floor,
                "maxResults": max_results,
            },
        )
        traversal_results = list(traversal_results)

        # Separate thoughts from knowledge nodes
        thoughts = []
        knowledge = []
        activated_thought_ids = set()
        activated_edge_ids = set()

        # Include entry points as activated thoughts
        for entry in entry_results:
            node = entry["node"]
            activated_thought_ids.add(node["_key"])
            thoughts.append({
                "node": node,
                "pathWeight": entry["similarity"],
                "depth": 0,
                "isEntryPoint": True,
            })

        for result in traversal_results:
            vertex = result["vertex"]
            collection = result["collection"]

            for ek in result.get("edgeKeys", []):
                activated_edge_ids.add(ek)

            if collection == "ThoughtNodes":
                activated_thought_ids.add(vertex["_key"])
                thoughts.append({
                    "node": vertex,
                    "pathWeight": result["pathWeight"],
                    "depth": result["depth"],
                    "isEntryPoint": False,
                })
            elif collection == "KnowledgeNodes":
                knowledge.append({
                    "node": vertex,
                    "pathWeight": result["pathWeight"],
                    "depth": result["depth"],
                })

        logger.info(
            "THOUGHT_TRAVERSE: client=%s thoughts=%d knowledge=%d edges=%d",
            client_id, len(thoughts), len(knowledge), len(activated_edge_ids),
        )

        return {
            "thoughts": thoughts,
            "knowledge": knowledge,
            "activated_thought_ids": list(activated_thought_ids),
            "activated_edge_ids": list(activated_edge_ids),
        }

    # ── Upsert thought (match-first strategy) ────────────────────────────

    async def upsert_thought(
        self,
        label: str,
        summary: str,
        thought_type: str,
        client_id: str,
        project_id: str = "",
        group_id: str = "",
        source_type: str = "",
        source_ref: str = "",
        related_entity_keys: list[str] | None = None,
        embedding_priority: int = 3,
    ) -> str:
        """
        Create or reinforce a thought. Match-first: cosine > 0.85 → reinforce.

        Returns the ThoughtNode _key.
        """
        # Embed label + summary
        embed_text = f"{label}: {summary}" if summary else label
        embedding = await self._embed_text(embed_text, priority=embedding_priority)

        # Search for existing match
        matches = await self.find_nearest(
            embedding=embedding,
            client_id=client_id,
            project_id=project_id,
            group_id=group_id,
            threshold=0.85,
            top_k=1,
        )

        now = datetime.now(timezone.utc).isoformat()

        if matches:
            # Reinforce existing
            existing = matches[0]["node"]
            key = existing["_key"]

            # Enrich summary if new is longer
            update = {
                "_key": key,
                "activationScore": min((existing.get("activationScore", 0.5) * 1.1) + 0.05, 1.0),
                "activationCount": (existing.get("activationCount", 0)) + 1,
                "lastActivatedAt": now,
                "updatedAt": now,
            }
            if len(summary) > len(existing.get("summary", "")):
                update["summary"] = summary

            await asyncio.to_thread(self.db.collection("ThoughtNodes").update, update)
            logger.info("THOUGHT_UPSERT: reinforced existing key=%s sim=%.3f", key, matches[0]["similarity"])
        else:
            # Create new
            key = f"thought_{uuid.uuid4().hex[:12]}"
            doc = {
                "_key": key,
                "type": thought_type,
                "label": label,
                "summary": summary,
                "clientId": client_id,
                "projectId": project_id or "",
                "groupId": group_id or "",
                "activationScore": 0.5,
                "lastActivatedAt": now,
                "activationCount": 1,
                "embedding": embedding,
                "sourceType": source_type,
                "sourceRef": source_ref,
                "createdAt": now,
                "updatedAt": now,
            }
            await asyncio.to_thread(self.db.collection("ThoughtNodes").insert, doc)
            logger.info("THOUGHT_UPSERT: created new key=%s type=%s label=%s", key, thought_type, label[:50])

        # Create anchors to related KnowledgeNodes
        if related_entity_keys:
            await self._create_anchors(key, related_entity_keys, client_id, "references_entity")

        return key

    # ── Create anchors ────────────────────────────────────────────────────

    async def _create_anchors(
        self,
        thought_key: str,
        knowledge_keys: list[str],
        client_id: str,
        anchor_type: str = "references_entity",
        weight: float = 0.8,
    ):
        """Create ThoughtAnchors from ThoughtNode to KnowledgeNodes."""
        nodes_col = self.db.collection("KnowledgeNodes")
        anchors_col = self.db.collection("ThoughtAnchors")

        def _insert():
            for k_key in knowledge_keys:
                arango_key = k_key.replace(":", "__")
                if not nodes_col.has(arango_key):
                    continue

                edge_key = f"{thought_key}_{anchor_type}_{arango_key}"
                if anchors_col.has(edge_key):
                    continue

                try:
                    anchors_col.insert({
                        "_key": edge_key,
                        "_from": f"ThoughtNodes/{thought_key}",
                        "_to": f"KnowledgeNodes/{arango_key}",
                        "anchorType": anchor_type,
                        "weight": weight,
                        "clientId": client_id,
                    })
                except Exception:
                    pass  # Duplicate key — already exists

        await asyncio.to_thread(_insert)

    # ── Create thought edge ───────────────────────────────────────────────

    async def create_thought_edge(
        self,
        from_key: str,
        to_key: str,
        edge_type: str,
        client_id: str,
        weight: float = 0.7,
    ):
        """Create ThoughtEdge between two ThoughtNodes."""
        edge_key = f"{from_key}_{edge_type}_{to_key}"
        edges_col = self.db.collection("ThoughtEdges")

        def _insert():
            if edges_col.has(edge_key):
                return
            try:
                edges_col.insert({
                    "_key": edge_key,
                    "_from": f"ThoughtNodes/{from_key}",
                    "_to": f"ThoughtNodes/{to_key}",
                    "edgeType": edge_type,
                    "weight": weight,
                    "clientId": client_id,
                    "lastTraversedAt": datetime.now(timezone.utc).isoformat(),
                    "traversalCount": 0,
                })
            except Exception:
                pass

        await asyncio.to_thread(_insert)

    # ── Hebbian reinforcement ─────────────────────────────────────────────

    async def reinforce(
        self,
        thought_keys: list[str],
        edge_keys: list[str],
    ):
        """Hebbian reinforcement of activated nodes and edges. Atomic AQL updates."""
        now = datetime.now(timezone.utc).isoformat()

        if thought_keys:
            aql_nodes = """
                FOR key IN @keys
                    LET t = DOCUMENT(CONCAT("ThoughtNodes/", key))
                    FILTER t != null
                    UPDATE t WITH {
                        activationScore: MIN(t.activationScore * 1.1 + 0.05, 1.0),
                        lastActivatedAt: @now,
                        activationCount: t.activationCount + 1
                    } IN ThoughtNodes
            """
            await asyncio.to_thread(
                self.db.aql.execute, aql_nodes,
                bind_vars={"keys": thought_keys, "now": now},
            )

        if edge_keys:
            aql_edges = """
                FOR key IN @keys
                    LET e = DOCUMENT(CONCAT("ThoughtEdges/", key))
                    FILTER e != null
                    UPDATE e WITH {
                        weight: MIN(e.weight * 1.05 + 0.02, 1.0),
                        lastTraversedAt: @now,
                        traversalCount: e.traversalCount + 1
                    } IN ThoughtEdges
            """
            await asyncio.to_thread(
                self.db.aql.execute, aql_edges,
                bind_vars={"keys": edge_keys, "now": now},
            )

        logger.info("THOUGHT_REINFORCE: nodes=%d edges=%d", len(thought_keys), len(edge_keys))

    # ── Stats ─────────────────────────────────────────────────────────────

    async def get_stats(self, client_id: str) -> dict:
        """Get Thought Map statistics for a client."""
        aql = """
            LET nodes = LENGTH(FOR t IN ThoughtNodes FILTER t.clientId == @cid RETURN 1)
            LET edges = LENGTH(FOR e IN ThoughtEdges FILTER e.clientId == @cid RETURN 1)
            LET anchors = LENGTH(FOR a IN ThoughtAnchors FILTER a.clientId == @cid RETURN 1)
            LET avgActivation = AVERAGE(FOR t IN ThoughtNodes FILTER t.clientId == @cid RETURN t.activationScore)
            LET orphans = LENGTH(
                FOR t IN ThoughtNodes
                    FILTER t.clientId == @cid
                    LET edgeCount = LENGTH(
                        FOR e IN ThoughtEdges
                            FILTER e._from == t._id OR e._to == t._id
                            LIMIT 1
                            RETURN 1
                    )
                    LET anchorCount = LENGTH(
                        FOR a IN ThoughtAnchors
                            FILTER a._from == t._id
                            LIMIT 1
                            RETURN 1
                    )
                    FILTER edgeCount == 0 AND anchorCount == 0
                    RETURN 1
            )
            RETURN {
                nodeCount: nodes,
                edgeCount: edges,
                anchorCount: anchors,
                avgActivation: avgActivation,
                orphanCount: orphans
            }
        """
        result = await asyncio.to_thread(
            self.db.aql.execute, aql,
            bind_vars={"cid": client_id},
        )
        stats = list(result)
        return stats[0] if stats else {}

    # ── Bootstrap (cold start) ────────────────────────────────────────────

    async def bootstrap(
        self,
        client_id: str,
        project_id: str = "",
        group_id: str = "",
        llm_call_fn=None,
    ) -> dict:
        """
        Cold start: seed Thought Map from existing KnowledgeNodes.

        1. Find top-100 KB nodes by degree
        2. LLM clusters them into thematic ThoughtNodes
        3. Create anchors + edges

        Args:
            llm_call_fn: async function(prompt, priority) -> str (LLM call from graph_service)

        Returns: stats dict with counts
        """
        # Step 1: Top nodes by degree (with fallback to 0-degree nodes)
        aql = """
            FOR n IN KnowledgeNodes
                FILTER (n.clientId == '' OR n.clientId == null OR n.clientId == @clientId)
                AND (n.projectId == '' OR n.projectId == null OR n.projectId == @projectId OR n.groupId == @groupId)
                LET degree = LENGTH(
                    FOR e IN KnowledgeEdges
                        FILTER e._from == n._id OR e._to == n._id
                        LIMIT 50
                        RETURN 1
                )
                SORT degree DESC
                LIMIT 100
                RETURN { key: n._key, label: n.label, type: n.type, description: n.description, degree: degree }
        """
        top_nodes = await asyncio.to_thread(
            self.db.aql.execute, aql,
            bind_vars={
                "clientId": client_id,
                "projectId": project_id or "",
                "groupId": group_id or "",
            },
        )
        top_nodes = list(top_nodes)

        logger.info("THOUGHT_BOOTSTRAP: found %d KB nodes for client=%s", len(top_nodes), client_id)
        if not top_nodes:
            return {"status": "empty", "thoughts_created": 0}

        if not llm_call_fn:
            logger.warning("THOUGHT_BOOTSTRAP: no llm_call_fn provided, cannot cluster")
            return {"status": "no_llm", "thoughts_created": 0}

        # Step 2: LLM clustering
        entities_text = "\n".join(
            f"- {n.get('label') or n.get('key', '?')} (type={n.get('type', '?')}, degree={n.get('degree', 0)}): {(n.get('description') or '')[:100]}"
            for n in top_nodes[:100]
            if n  # skip None results
        )

        prompt = f"""You are a knowledge organizer. Group these entities into 10-20 thematic clusters.
Each cluster represents a high-level topic, problem, decision, or area of work.

Entities:
{entities_text}

Return JSON:
{{
  "clusters": [
    {{
      "type": "topic|decision|problem|insight",
      "label": "short identifier",
      "summary": "1-2 sentence description of what this cluster is about",
      "entity_labels": ["entity1", "entity2", ...]
    }}
  ]
}}"""

        try:
            response = await llm_call_fn(prompt, priority=2)
            logger.info("THOUGHT_BOOTSTRAP: LLM response length=%d, first 200 chars: %s", len(response), response[:200])
            if "```json" in response:
                response = response.split("```json")[1].split("```")[0]
            elif "```" in response:
                response = response.split("```")[1].split("```")[0]
            data = json.loads(response)
            logger.info("THOUGHT_BOOTSTRAP: parsed %d clusters from LLM", len(data.get("clusters", [])))
        except Exception as e:
            logger.error("THOUGHT_BOOTSTRAP: LLM clustering failed: %s (response: %s)", e, response[:300] if response else "empty")
            return {"status": "llm_error", "thoughts_created": 0, "error": str(e)}

        # Step 3: Create ThoughtNodes + anchors
        created_keys = []
        label_to_key_map = {n.get("label", "").lower(): n["key"] for n in top_nodes if n and n.get("label")}

        for cluster in data.get("clusters", []):
            # Find matching entity keys
            entity_keys = []
            for entity_label in cluster.get("entity_labels", []):
                matched_key = label_to_key_map.get(entity_label.lower())
                if matched_key:
                    entity_keys.append(matched_key)

            key = await self.upsert_thought(
                label=cluster.get("label", ""),
                summary=cluster.get("summary", ""),
                thought_type=cluster.get("type", "topic"),
                client_id=client_id,
                project_id=project_id,
                group_id=group_id,
                source_type="bootstrap",
                related_entity_keys=entity_keys,
                embedding_priority=2,
            )
            created_keys.append(key)

        # Step 4: Create edges between related clusters (co-occurrence)
        for i, key_a in enumerate(created_keys):
            for key_b in created_keys[i + 1:]:
                await self.create_thought_edge(
                    from_key=key_a,
                    to_key=key_b,
                    edge_type="same_domain",
                    client_id=client_id,
                    weight=0.3,
                )

        logger.info("THOUGHT_BOOTSTRAP: client=%s created %d thoughts", client_id, len(created_keys))
        return {"status": "ok", "thoughts_created": len(created_keys)}
