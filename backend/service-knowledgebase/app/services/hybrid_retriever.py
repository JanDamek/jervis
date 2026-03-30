"""
Hybrid Retriever - Advanced RAG + Graph retrieval with reranking.

This module implements sophisticated hybrid search combining:
1. Vector similarity search (RAG)
2. Graph traversal expansion
3. Entity-based direct lookup
4. Reciprocal Rank Fusion (RRF) for score combination
5. Source diversity and deduplication
"""

import asyncio
import logging
import re
import time
from dataclasses import dataclass, field
from typing import Optional
from app.api.models import (
    RetrievalRequest, EvidencePack, EvidenceItem,
    TraversalRequest, TraversalSpec, GraphNode,
    CREDIBILITY_WEIGHTS, BRANCH_ROLE_BOOST, SourceCredibility
)
from app.services.rag_service import RagService
from app.services.graph_service import GraphService
from app.services.normalizer import normalize_graph_ref, extract_namespace

logger = logging.getLogger(__name__)


@dataclass
class ScoredChunk:
    """Internal representation of a scored chunk for ranking."""
    chunk_id: str
    content: str
    source_urn: str
    rag_score: float = 0.0          # Score from vector search
    graph_score: float = 0.0         # Score from graph expansion
    entity_score: float = 0.0        # Score from entity match
    combined_score: float = 0.0      # Final combined score
    credibility_boost: float = 1.0   # Credibility multiplier applied
    graph_refs: list = field(default_factory=list)
    metadata: dict = field(default_factory=dict)
    source: str = "rag"              # "rag", "graph", "entity"
    graph_distance: int = 0          # Hops from seed node
    credibility: str = ""            # SourceCredibility tier
    branch_scope: str = ""           # Which branch this info is scoped to
    branch_role: str = ""            # "default", "protected", "active", etc.


class HybridRetriever:
    """
    Advanced hybrid retriever combining RAG and Graph search.
    """

    # Weights for score combination
    RAG_WEIGHT = 0.6
    GRAPH_WEIGHT = 0.3
    ENTITY_WEIGHT = 0.1

    # RRF constant (typical value is 60)
    RRF_K = 60

    def __init__(self, rag_service: RagService, graph_service: GraphService):
        self.rag_service = rag_service
        self.graph_service = graph_service

    async def retrieve(
        self,
        request: RetrievalRequest,
        expand_graph: bool = True,
        extract_entities: bool = True,
        use_rrf: bool = True,
        max_graph_hops: int = 2,
        max_seeds: int = 10,
        diversity_factor: float = 0.7,
        embedding_priority: int | None = None
    ) -> EvidencePack:
        """
        Perform hybrid retrieval with advanced ranking.

        Args:
            request: The retrieval request
            expand_graph: Whether to expand results using graph
            extract_entities: Whether to extract entities from query
            use_rrf: Whether to use Reciprocal Rank Fusion
            max_graph_hops: Maximum hops for graph traversal
            max_seeds: Maximum seed nodes for graph expansion
            diversity_factor: Source diversity factor (0-1)
            embedding_priority: Optional explicit priority for embedding

        Returns:
            EvidencePack with ranked results
        """
        all_chunks: dict[str, ScoredChunk] = {}
        t_start = time.monotonic()

        # 1. RAG Vector Search
        t0 = time.monotonic()
        rag_evidence = await self.rag_service.retrieve(request, embedding_priority=embedding_priority)
        logger.info("HYBRID: RAG returned %d items (%.0fms)", len(rag_evidence.items), (time.monotonic() - t0) * 1000)
        for i, item in enumerate(rag_evidence.items):
            chunk_id = item.metadata.get("id", f"rag_{i}")
            chunk = ScoredChunk(
                chunk_id=chunk_id,
                content=item.content,
                source_urn=item.sourceUrn,
                rag_score=item.score,
                graph_refs=item.metadata.get("graphRefs", []),
                metadata=item.metadata,
                source="rag",
                credibility=item.credibility or item.metadata.get("credibility", ""),
                branch_scope=item.branchScope or item.metadata.get("branchScope", ""),
                branch_role=item.metadata.get("branchRole", ""),
            )
            all_chunks[chunk_id] = chunk

        # 2. Extract entities from query (optional)
        if extract_entities:
            query_entities = self._extract_query_entities(request.query)
            if query_entities:
                entity_chunks = await self._fetch_entity_chunks(
                    query_entities,
                    request.clientId,
                    request.projectId,
                    getattr(request, 'groupId', None)
                )
                for chunk in entity_chunks:
                    if chunk.chunk_id in all_chunks:
                        all_chunks[chunk.chunk_id].entity_score = chunk.entity_score
                    else:
                        all_chunks[chunk.chunk_id] = chunk

        # 3. Graph Expansion (optional)
        if expand_graph:
            t0 = time.monotonic()
            seed_nodes = self._collect_seed_nodes(all_chunks, max_seeds)
            if seed_nodes:
                graph_chunks = await self._expand_via_graph(
                    seed_nodes,
                    request.clientId,
                    request.projectId,
                    max_graph_hops,
                    getattr(request, 'groupId', None)
                )
                for chunk in graph_chunks:
                    if chunk.chunk_id in all_chunks:
                        # Update graph score if better
                        existing = all_chunks[chunk.chunk_id]
                        if chunk.graph_score > existing.graph_score:
                            existing.graph_score = chunk.graph_score
                            existing.graph_distance = chunk.graph_distance
                    else:
                        all_chunks[chunk.chunk_id] = chunk

        # 4. Combine scores
        if use_rrf:
            self._apply_rrf_scoring(all_chunks)
        else:
            self._apply_weighted_scoring(all_chunks)

        # 5. Apply diversity penalty
        if diversity_factor < 1.0:
            self._apply_diversity_penalty(all_chunks, diversity_factor)

        # 5b. Apply source credibility boost
        self._apply_credibility_boost(all_chunks)

        # 6. Sort and filter
        sorted_chunks = sorted(
            all_chunks.values(),
            key=lambda x: x.combined_score,
            reverse=True
        )

        # Filter by min confidence
        filtered = [c for c in sorted_chunks if c.combined_score >= request.minConfidence]

        # Limit results
        final = filtered[:request.maxResults]

        # Convert to EvidenceItems
        items = []
        for chunk in final:
            items.append(EvidenceItem(
                content=chunk.content,
                score=chunk.combined_score,
                sourceUrn=chunk.source_urn,
                credibility=chunk.credibility or None,
                branchScope=chunk.branch_scope or None,
                metadata={
                    "id": chunk.chunk_id,
                    "source": chunk.source,
                    "ragScore": chunk.rag_score,
                    "graphScore": chunk.graph_score,
                    "entityScore": chunk.entity_score,
                    "credibilityBoost": chunk.credibility_boost,
                    "branchRole": chunk.branch_role,
                    "graphDistance": chunk.graph_distance,
                    "graphRefs": chunk.graph_refs,
                    **chunk.metadata
                }
            ))

        return EvidencePack(items=items)

    def _extract_query_entities(self, query: str) -> list[str]:
        """
        Extract potential entity references from query.

        Looks for:
        - Ticket patterns: TASK-123, JIRA-456, BUG-789
        - User mentions: @john, john.doe@email.com
        - File patterns: *.kt, Service.java
        - Explicit refs: jira:TASK-123, user:john
        - CamelCase identifiers: MyService, UserController
        - Version strings: v1.2.3, 2.0.0
        - Qualified class names: com.jervis.MyClass
        """
        entities = []

        # Ticket patterns
        ticket_pattern = r'\b([A-Z]+-\d+)\b'
        for match in re.finditer(ticket_pattern, query):
            entities.append(f"jira:{match.group(1).lower()}")

        # User mentions @name
        mention_pattern = r'@(\w+)'
        for match in re.finditer(mention_pattern, query):
            entities.append(f"user:{match.group(1).lower()}")

        # Email addresses
        email_pattern = r'\b([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})\b'
        for match in re.finditer(email_pattern, query):
            entities.append(normalize_graph_ref(f"user:{match.group(1)}"))

        # File patterns with extension
        file_pattern = r'\b([\w/]+\.(?:kt|java|py|ts|tsx|js|jsx|go|rs|cpp|c|h|yaml|yml|json|toml|xml|sql|sh|md))\b'
        for match in re.finditer(file_pattern, query.lower()):
            entities.append(f"file:{match.group(1)}")

        # CamelCase identifiers (class/service names, min 2 parts)
        camel_pattern = r'\b([A-Z][a-z]+(?:[A-Z][a-z]+)+)\b'
        for match in re.finditer(camel_pattern, query):
            entities.append(normalize_graph_ref(f"entity:{match.group(1)}"))

        # Qualified class names (com.jervis.MyClass)
        qualified_pattern = r'\b([a-z]+(?:\.[a-z]+)+\.[A-Z]\w+)\b'
        for match in re.finditer(qualified_pattern, query):
            entities.append(normalize_graph_ref(f"file:{match.group(1)}"))

        # Version strings
        version_pattern = r'\bv?(\d+\.\d+(?:\.\d+)?)\b'
        for match in re.finditer(version_pattern, query):
            entities.append(f"version:{match.group(1)}")

        # Explicit namespace:value patterns
        explicit_pattern = r'\b(\w+:\S+)\b'
        for match in re.finditer(explicit_pattern, query):
            ref = match.group(1)
            if extract_namespace(ref):
                entities.append(normalize_graph_ref(ref))

        return list(set(entities))

    async def _fetch_entity_chunks(
        self,
        entities: list[str],
        client_id: str,
        project_id: str,
        group_id: str = None
    ) -> list[ScoredChunk]:
        """
        Fetch chunks directly associated with entities.
        """
        chunks = []

        for entity in entities:
            try:
                chunk_ids = await self.graph_service.get_node_chunks(entity, client_id)
                if chunk_ids:
                    fetched = await self.rag_service.get_chunks_by_ids(chunk_ids[:5])
                    for i, chunk in enumerate(fetched):
                        # Score decreases for later chunks
                        score = 0.9 - (i * 0.1)
                        chunks.append(ScoredChunk(
                            chunk_id=chunk["id"],
                            content=chunk["content"],
                            source_urn=chunk["sourceUrn"],
                            entity_score=score,
                            graph_refs=chunk.get("graphRefs", []),
                            source="entity",
                            metadata={"matchedEntity": entity}
                        ))
            except Exception as e:
                logger.warning("Entity chunk fetch failed for %s: %s", entity, e)

        return chunks

    def _collect_seed_nodes(
        self,
        chunks: dict[str, ScoredChunk],
        max_seeds: int
    ) -> list[str]:
        """
        Collect seed nodes from chunks for graph expansion.

        Prioritizes nodes from higher-scored chunks.
        """
        # Sort chunks by score
        sorted_chunks = sorted(
            chunks.values(),
            key=lambda x: x.rag_score + x.entity_score,
            reverse=True
        )

        seeds = []
        seen = set()

        for chunk in sorted_chunks:
            for ref in chunk.graph_refs:
                if ref not in seen:
                    seeds.append(ref)
                    seen.add(ref)
                    if len(seeds) >= max_seeds:
                        return seeds

        return seeds

    async def _traverse_single_seed(
        self,
        seed_idx: int,
        seed_key: str,
        client_id: str,
        project_id: str,
        max_hops: int,
        group_id: str = None,
    ) -> list[ScoredChunk]:
        """Traverse a single seed node and return scored chunks."""
        seed_priority_factor = 1.0 - (seed_idx * 0.05)
        chunks = []
        try:
            t0 = time.monotonic()
            traversal_request = TraversalRequest(
                clientId=client_id,
                projectId=project_id,
                groupId=group_id,
                startKey=seed_key,
                spec=TraversalSpec(direction="ANY", minDepth=1, maxDepth=max_hops)
            )
            related_nodes = await self.graph_service.traverse(traversal_request)
            elapsed = (time.monotonic() - t0) * 1000
            logger.info("GRAPH_EXPAND: seed[%d] %s → %d nodes (%.0fms)", seed_idx, seed_key, len(related_nodes), elapsed)

            for node in related_nodes:
                for chunk_id in node.properties.get("ragChunks", []):
                    distance = node.properties.get("_depth", 1)
                    distance_factor = 1.0 / (distance + 1)
                    graph_score = 0.8 * seed_priority_factor * distance_factor
                    chunks.append(ScoredChunk(
                        chunk_id=chunk_id,
                        content="",
                        source_urn="",
                        graph_score=graph_score,
                        graph_distance=distance,
                        source="graph",
                        metadata={"seedNode": seed_key, "discoveredVia": node.key}
                    ))
        except Exception as e:
            logger.warning("Graph expansion failed for %s: %s", seed_key, e)
        return chunks

    async def _expand_via_graph(
        self,
        seed_nodes: list[str],
        client_id: str,
        project_id: str,
        max_hops: int,
        group_id: str = None
    ) -> list[ScoredChunk]:
        """
        Expand search via graph traversal from seed nodes (parallel).
        """
        from ..core.config import settings as kb_settings
        max_expansion_chunks = kb_settings.MAX_GRAPH_EXPANSION_CHUNKS

        t0 = time.monotonic()

        # Run all seed traversals in parallel
        tasks = [
            self._traverse_single_seed(idx, key, client_id, project_id, max_hops, group_id)
            for idx, key in enumerate(seed_nodes)
        ]
        results = await asyncio.gather(*tasks)

        # Merge and deduplicate
        chunks = []
        visited_chunks = set()
        for seed_chunks in results:
            for chunk in seed_chunks:
                if chunk.chunk_id not in visited_chunks:
                    visited_chunks.add(chunk.chunk_id)
                    chunks.append(chunk)
                    if len(chunks) >= max_expansion_chunks:
                        logger.info("GRAPH_EXPAND: chunk cap reached (%d), stopping", max_expansion_chunks)
                        break
            if len(chunks) >= max_expansion_chunks:
                break

        # Fetch content for graph-discovered chunks
        if chunks:
            t1 = time.monotonic()
            chunk_ids = [c.chunk_id for c in chunks]
            fetched = await self.rag_service.get_chunks_by_ids(chunk_ids)
            fetched_map = {c["id"]: c for c in fetched}
            for chunk in chunks:
                if chunk.chunk_id in fetched_map:
                    data = fetched_map[chunk.chunk_id]
                    chunk.content = data["content"]
                    chunk.source_urn = data["sourceUrn"]
                    chunk.graph_refs = data.get("graphRefs", [])
            logger.info("GRAPH_EXPAND: fetched %d/%d chunks (%.0fms)", len(fetched_map), len(chunk_ids), (time.monotonic() - t1) * 1000)

        total_ms = (time.monotonic() - t0) * 1000
        logger.info("GRAPH_EXPAND: total %d seeds → %d chunks (%.0fms)", len(seed_nodes), len(chunks), total_ms)

        return [c for c in chunks if c.content]

    def _apply_rrf_scoring(self, chunks: dict[str, ScoredChunk]):
        """
        Apply Reciprocal Rank Fusion scoring.

        RRF combines rankings from different sources using:
        score = sum(1 / (k + rank)) for each source
        """
        # Create rankings for each source
        rag_ranking = sorted(
            [c for c in chunks.values() if c.rag_score > 0],
            key=lambda x: x.rag_score,
            reverse=True
        )
        graph_ranking = sorted(
            [c for c in chunks.values() if c.graph_score > 0],
            key=lambda x: x.graph_score,
            reverse=True
        )
        entity_ranking = sorted(
            [c for c in chunks.values() if c.entity_score > 0],
            key=lambda x: x.entity_score,
            reverse=True
        )

        # Build rank lookup dicts (O(n) instead of O(n²) from .index() calls)
        rag_rank = {id(c): i + 1 for i, c in enumerate(rag_ranking)}
        graph_rank = {id(c): i + 1 for i, c in enumerate(graph_ranking)}
        entity_rank = {id(c): i + 1 for i, c in enumerate(entity_ranking)}

        # Calculate RRF scores
        for chunk in chunks.values():
            rrf_score = 0.0
            cid = id(chunk)

            # RAG contribution
            if cid in rag_rank:
                rrf_score += self.RAG_WEIGHT * (1.0 / (self.RRF_K + rag_rank[cid]))

            # Graph contribution
            if cid in graph_rank:
                rrf_score += self.GRAPH_WEIGHT * (1.0 / (self.RRF_K + graph_rank[cid]))

            # Entity contribution
            if cid in entity_rank:
                rrf_score += self.ENTITY_WEIGHT * (1.0 / (self.RRF_K + entity_rank[cid]))

            chunk.combined_score = rrf_score

        # Normalize to 0-1 range
        max_score = max((c.combined_score for c in chunks.values()), default=1.0)
        if max_score > 0:
            for chunk in chunks.values():
                chunk.combined_score /= max_score

    def _apply_weighted_scoring(self, chunks: dict[str, ScoredChunk]):
        """
        Apply simple weighted average scoring.
        """
        for chunk in chunks.values():
            chunk.combined_score = (
                self.RAG_WEIGHT * chunk.rag_score +
                self.GRAPH_WEIGHT * chunk.graph_score +
                self.ENTITY_WEIGHT * chunk.entity_score
            )

    def _apply_diversity_penalty(
        self,
        chunks: dict[str, ScoredChunk],
        factor: float
    ):
        """
        Apply diversity penalty to avoid too many results from same source.

        Chunks from the same sourceUrn get progressively penalized.
        """
        source_counts: dict[str, int] = {}

        # Sort by current score to process best first
        sorted_chunks = sorted(
            chunks.values(),
            key=lambda x: x.combined_score,
            reverse=True
        )

        for chunk in sorted_chunks:
            source = chunk.source_urn
            count = source_counts.get(source, 0)

            if count > 0:
                # Apply penalty: score *= factor^count
                penalty = factor ** count
                chunk.combined_score *= penalty

            source_counts[source] = count + 1

    def _apply_credibility_boost(self, chunks: dict[str, ScoredChunk]):
        """
        Boost scores based on source credibility, branch role, and recency.

        Credibility multiplier:
          verified_fact=1.0, official_doc=0.95, structured_data=0.85,
          code_analysis=0.75, llm_extracted=0.60, inferred=0.40

        Branch role multiplier (for code-related chunks):
          default=1.0, protected=0.95, active=0.75, merged=0.50, stale=0.30

        Recency boost: newer content gets a mild boost (up to 1.15x for today,
        decaying to 1.0x for content older than 30 days).

        Combined: score *= credibility_weight * branch_boost * recency_boost
        Chunks without credibility info get a neutral 0.70 default (between
        STRUCTURED_DATA and CODE_ANALYSIS — conservative assumption).
        """
        from datetime import datetime, timezone

        DEFAULT_CREDIBILITY_WEIGHT = 0.70
        RECENCY_MAX_BOOST = 1.15  # Max 15% boost for very recent content
        RECENCY_HALF_LIFE_DAYS = 14  # Boost halves every 14 days

        now = datetime.now(timezone.utc)

        for chunk in chunks.values():
            # Credibility weight
            cred_weight = DEFAULT_CREDIBILITY_WEIGHT
            if chunk.credibility:
                cred_weight = CREDIBILITY_WEIGHTS.get(chunk.credibility, DEFAULT_CREDIBILITY_WEIGHT)

            # Branch role boost (only applies if branch-scoped)
            branch_boost = 1.0
            if chunk.branch_role:
                branch_boost = BRANCH_ROLE_BOOST.get(chunk.branch_role, 0.75)

            # Recency boost (mild — based on observedAt timestamp)
            recency_boost = 1.0
            observed_at = chunk.metadata.get("observedAt", "")
            if observed_at:
                try:
                    if isinstance(observed_at, str) and observed_at:
                        obs_dt = datetime.fromisoformat(observed_at.replace("Z", "+00:00"))
                        if obs_dt.tzinfo is None:
                            obs_dt = obs_dt.replace(tzinfo=timezone.utc)
                        age_days = max(0, (now - obs_dt).days)
                        # Exponential decay: 1.0 + (MAX_BOOST - 1.0) * 0.5^(age/half_life)
                        decay = 0.5 ** (age_days / RECENCY_HALF_LIFE_DAYS)
                        recency_boost = 1.0 + (RECENCY_MAX_BOOST - 1.0) * decay
                except (ValueError, TypeError):
                    pass

            total_boost = cred_weight * branch_boost * recency_boost
            chunk.combined_score *= total_boost
            chunk.credibility_boost = total_boost


# Convenience function for simple usage
async def hybrid_retrieve(
    rag_service: RagService,
    graph_service: GraphService,
    request: RetrievalRequest
) -> EvidencePack:
    """
    Convenience function for hybrid retrieval.
    """
    retriever = HybridRetriever(rag_service, graph_service)
    return await retriever.retrieve(
        request,
        expand_graph=request.expandGraph
    )
