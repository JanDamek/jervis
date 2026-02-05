"""
Hybrid Retriever - Advanced RAG + Graph retrieval with reranking.

This module implements sophisticated hybrid search combining:
1. Vector similarity search (RAG)
2. Graph traversal expansion
3. Entity-based direct lookup
4. Reciprocal Rank Fusion (RRF) for score combination
5. Source diversity and deduplication
"""

from dataclasses import dataclass, field
from typing import Optional
from app.api.models import (
    RetrievalRequest, EvidencePack, EvidenceItem,
    TraversalRequest, TraversalSpec, GraphNode
)
from app.services.rag_service import RagService
from app.services.graph_service import GraphService
from app.services.normalizer import normalize_graph_ref, extract_namespace
import re


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
    graph_refs: list = field(default_factory=list)
    metadata: dict = field(default_factory=dict)
    source: str = "rag"              # "rag", "graph", "entity"
    graph_distance: int = 0          # Hops from seed node


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
        diversity_factor: float = 0.7
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

        Returns:
            EvidencePack with ranked results
        """
        all_chunks: dict[str, ScoredChunk] = {}

        # 1. RAG Vector Search
        rag_evidence = await self.rag_service.retrieve(request)
        for i, item in enumerate(rag_evidence.items):
            chunk_id = item.metadata.get("id", f"rag_{i}")
            chunk = ScoredChunk(
                chunk_id=chunk_id,
                content=item.content,
                source_urn=item.sourceUrn,
                rag_score=item.score,
                graph_refs=item.metadata.get("graphRefs", []),
                metadata=item.metadata,
                source="rag"
            )
            all_chunks[chunk_id] = chunk

        # 2. Extract entities from query (optional)
        if extract_entities:
            query_entities = self._extract_query_entities(request.query)
            if query_entities:
                entity_chunks = await self._fetch_entity_chunks(
                    query_entities,
                    request.clientId,
                    request.projectId
                )
                for chunk in entity_chunks:
                    if chunk.chunk_id in all_chunks:
                        all_chunks[chunk.chunk_id].entity_score = chunk.entity_score
                    else:
                        all_chunks[chunk.chunk_id] = chunk

        # 3. Graph Expansion (optional)
        if expand_graph:
            seed_nodes = self._collect_seed_nodes(all_chunks, max_seeds)
            if seed_nodes:
                graph_chunks = await self._expand_via_graph(
                    seed_nodes,
                    request.clientId,
                    request.projectId,
                    max_graph_hops
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
                metadata={
                    "id": chunk.chunk_id,
                    "source": chunk.source,
                    "ragScore": chunk.rag_score,
                    "graphScore": chunk.graph_score,
                    "entityScore": chunk.entity_score,
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
        file_pattern = r'\b(\w+\.(?:kt|java|py|ts|js|go|rs|cpp|c|h))\b'
        for match in re.finditer(file_pattern, query.lower()):
            entities.append(f"file:{match.group(1)}")

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
        project_id: str
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
                print(f"Entity chunk fetch failed for {entity}: {e}")

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

    async def _expand_via_graph(
        self,
        seed_nodes: list[str],
        client_id: str,
        project_id: str,
        max_hops: int
    ) -> list[ScoredChunk]:
        """
        Expand search via graph traversal from seed nodes.
        """
        chunks = []
        visited_chunks = set()

        for seed_idx, seed_key in enumerate(seed_nodes):
            # Score penalty based on seed priority
            seed_priority_factor = 1.0 - (seed_idx * 0.05)

            try:
                traversal_request = TraversalRequest(
                    clientId=client_id,
                    projectId=project_id,
                    startKey=seed_key,
                    spec=TraversalSpec(direction="ANY", minDepth=1, maxDepth=max_hops)
                )
                related_nodes = await self.graph_service.traverse(traversal_request)

                for node in related_nodes:
                    node_chunks = node.properties.get("ragChunks", [])

                    for chunk_id in node_chunks:
                        if chunk_id in visited_chunks:
                            continue
                        visited_chunks.add(chunk_id)

                        # Calculate graph score based on distance
                        # Closer nodes get higher scores
                        distance = 1  # TODO: Get actual distance from traversal
                        distance_factor = 1.0 / (distance + 1)
                        graph_score = 0.8 * seed_priority_factor * distance_factor

                        chunks.append(ScoredChunk(
                            chunk_id=chunk_id,
                            content="",  # Will be fetched if needed
                            source_urn="",
                            graph_score=graph_score,
                            graph_distance=distance,
                            source="graph",
                            metadata={
                                "seedNode": seed_key,
                                "discoveredVia": node.key
                            }
                        ))

            except Exception as e:
                print(f"Graph expansion failed for {seed_key}: {e}")

        # Fetch content for graph-discovered chunks
        if chunks:
            chunk_ids = [c.chunk_id for c in chunks]
            fetched = await self.rag_service.get_chunks_by_ids(chunk_ids)
            fetched_map = {c["id"]: c for c in fetched}

            for chunk in chunks:
                if chunk.chunk_id in fetched_map:
                    data = fetched_map[chunk.chunk_id]
                    chunk.content = data["content"]
                    chunk.source_urn = data["sourceUrn"]
                    chunk.graph_refs = data.get("graphRefs", [])

        return [c for c in chunks if c.content]  # Only return chunks with content

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

        # Calculate RRF scores
        for chunk in chunks.values():
            rrf_score = 0.0

            # RAG contribution
            if chunk in rag_ranking:
                rank = rag_ranking.index(chunk) + 1
                rrf_score += self.RAG_WEIGHT * (1.0 / (self.RRF_K + rank))

            # Graph contribution
            if chunk in graph_ranking:
                rank = graph_ranking.index(chunk) + 1
                rrf_score += self.GRAPH_WEIGHT * (1.0 / (self.RRF_K + rank))

            # Entity contribution
            if chunk in entity_ranking:
                rank = entity_ranking.index(chunk) + 1
                rrf_score += self.ENTITY_WEIGHT * (1.0 / (self.RRF_K + rank))

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
