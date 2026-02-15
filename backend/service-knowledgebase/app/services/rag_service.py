import asyncio
import logging
import uuid
import httpx

from langchain_ollama import OllamaEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.core.config import settings
from app.db.weaviate import get_weaviate_client
from app.api.models import IngestRequest, RetrievalRequest, EvidenceItem, EvidencePack
import weaviate.classes.config as wvc
import weaviate.classes.query as wvq

logger = logging.getLogger(__name__)


class RagService:
    def __init__(self):
        self.embeddings = OllamaEmbeddings(
            base_url=settings.OLLAMA_EMBEDDING_BASE_URL,
            model=settings.EMBEDDING_MODEL
        )
        # Determine priority based on KB_MODE
        # "read" = orchestrator queries (ORCHESTRATOR_EMBEDDING = 1)
        # "write" = indexing (BACKGROUND = 4)
        self.embedding_priority = 1 if settings.KB_MODE == "read" else 4
        self.http_client = httpx.AsyncClient(timeout=3600.0)  # 1 hour — CPU embedding is slow, queues behind other work
        self.text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=1000,
            chunk_overlap=200
        )
        self.client = get_weaviate_client()
        self._ensure_schema()

    def _ensure_schema(self):
        if not self.client.collections.exists("KnowledgeChunk"):
            self.client.collections.create(
                name="KnowledgeChunk",
                vectorizer_config=wvc.Configure.Vectorizer.none(),  # We provide vectors
                properties=[
                    wvc.Property(name="content", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="sourceUrn", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="clientId", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="projectId", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="kind", data_type=wvc.DataType.TEXT),
                    # Bidirectional linking: list of graph node keys referenced in this chunk
                    wvc.Property(name="groupId", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="graphRefs", data_type=wvc.DataType.TEXT_ARRAY),
                ]
            )

    async def _embed_with_priority(self, text: str | list[str], priority: int | None = None) -> list[float] | list[list[float]]:
        """Embed text with priority header for router.

        Priority can be passed explicitly or falls back to KB_MODE-based default:
        - KB_MODE="read" → Priority 1 (ORCHESTRATOR_EMBEDDING, co-located with CRITICAL on GPU)
        - KB_MODE="write" → Priority 4 (BACKGROUND, CPU fallback)
        """
        is_batch = isinstance(text, list)
        prompt = text if is_batch else [text]

        # Use explicit priority if provided, otherwise fall back to KB_MODE default
        effective_priority = priority if priority is not None else self.embedding_priority

        url = f"{settings.OLLAMA_EMBEDDING_BASE_URL}/api/embed"
        payload = {
            "model": settings.EMBEDDING_MODEL,
            "input": prompt,
        }
        headers = {"X-Ollama-Priority": str(effective_priority)}

        logger.info("RAG: EMBEDDING model=%s priority=%d (explicit=%s default=%d)",
                    settings.EMBEDDING_MODEL, effective_priority, priority, self.embedding_priority)

        try:
            resp = await self.http_client.post(url, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()
            embeddings = data.get("embeddings", [])
            return embeddings if is_batch else embeddings[0]
        except Exception as e:
            logger.error("Embedding failed: %s", e)
            raise

    async def ingest(
        self,
        request: IngestRequest,
        graph_refs: list[str] = None,
        embedding_priority: int | None = None
    ) -> tuple[int, list[str]]:
        """
        Ingest content into RAG store.

        Args:
            request: The ingest request
            graph_refs: Optional list of graph node keys referenced in this content
            embedding_priority: Optional explicit priority for embedding (overrides KB_MODE default)

        Returns:
            Tuple of (chunk_count, list of chunk UUIDs)
        """
        logger.info(
            "RAG_WRITE: START sourceUrn=%s clientId=%s projectId=%s groupId=%s kind=%s content_len=%d priority=%s",
            request.sourceUrn, request.clientId, request.projectId or "",
            request.groupId or "", request.kind or "", len(request.content), embedding_priority
        )

        chunks = self.text_splitter.split_text(request.content)
        logger.info("RAG_WRITE: SPLIT sourceUrn=%s → %d chunks", request.sourceUrn, len(chunks))

        # Batch embed all chunks at once (instead of per-chunk embed_query)
        logger.info("RAG_WRITE: EMBEDDING sourceUrn=%s chunks=%d model=%s priority=%s",
                    request.sourceUrn, len(chunks), settings.EMBEDDING_MODEL, embedding_priority)
        vectors = await self._embed_with_priority(chunks, priority=embedding_priority)
        logger.info("RAG_WRITE: EMBEDDED sourceUrn=%s vectors=%d", request.sourceUrn, len(vectors))

        def _weaviate_batch_insert():
            collection = self.client.collections.get("KnowledgeChunk")
            chunk_ids = []
            with collection.batch.dynamic() as batch:
                for i, chunk in enumerate(chunks):
                    chunk_id = str(uuid.uuid4())
                    batch.add_object(
                        uuid=chunk_id,
                        properties={
                            "content": chunk,
                            "sourceUrn": request.sourceUrn,
                            "clientId": request.clientId,
                            "projectId": request.projectId or "",
                            "groupId": request.groupId or "",
                            "kind": request.kind or "",
                            "graphRefs": graph_refs or [],
                        },
                        vector=vectors[i]
                    )
                    chunk_ids.append(chunk_id)
                    if i < 3:  # Log first 3 chunks for debugging
                        logger.info(
                            "RAG_WRITE: CHUNK[%d] id=%s clientId=%s projectId=%s groupId=%s kind=%s content_preview=%s",
                            i, chunk_id, request.clientId, request.projectId or "",
                            request.groupId or "", request.kind or "", chunk[:100]
                        )
            return chunk_ids

        chunk_ids = await asyncio.to_thread(_weaviate_batch_insert)
        logger.info(
            "RAG_WRITE: COMPLETE sourceUrn=%s chunks_written=%d clientId=%s projectId=%s chunk_ids=%s",
            request.sourceUrn, len(chunk_ids), request.clientId, request.projectId or "",
            chunk_ids[:3] if len(chunk_ids) > 3 else chunk_ids
        )
        return len(chunk_ids), chunk_ids

    async def update_chunk_graph_refs(self, chunk_id: str, graph_refs: list[str]) -> bool:
        """
        Update graphRefs for an existing chunk (bidirectional linking).

        Called after graph extraction to link chunks to discovered entities.
        """
        def _update():
            collection = self.client.collections.get("KnowledgeChunk")
            collection.data.update(
                uuid=chunk_id,
                properties={"graphRefs": graph_refs}
            )

        try:
            await asyncio.to_thread(_update)
            return True
        except Exception as e:
            logger.warning("Failed to update chunk graphRefs: %s", e)
            return False

    async def get_chunks_by_ids(self, chunk_ids: list[str]) -> list[dict]:
        """
        Fetch chunks by their UUIDs.

        Used for evidence retrieval from graph nodes.
        """
        def _fetch():
            collection = self.client.collections.get("KnowledgeChunk")
            results = []
            for chunk_id in chunk_ids:
                try:
                    obj = collection.query.fetch_object_by_id(chunk_id)
                    if obj:
                        results.append({
                            "id": chunk_id,
                            "content": obj.properties.get("content", ""),
                            "sourceUrn": obj.properties.get("sourceUrn", ""),
                            "graphRefs": obj.properties.get("graphRefs", []),
                        })
                except Exception:
                    pass
            return results

        return await asyncio.to_thread(_fetch)

    async def purge_by_source(self, source_urn: str) -> tuple[int, list[str]]:
        """
        Delete all RAG chunks matching a sourceUrn.

        Returns:
            Tuple of (chunks_deleted, list of deleted chunk UUIDs)
        """
        def _purge():
            collection = self.client.collections.get("KnowledgeChunk")
            response = collection.query.fetch_objects(
                filters=wvq.Filter.by_property("sourceUrn").equal(source_urn),
                limit=10000,
                return_properties=["sourceUrn"],
            )
            deleted_ids = []
            for obj in response.objects:
                chunk_id = str(obj.uuid)
                try:
                    collection.data.delete_by_id(obj.uuid)
                    deleted_ids.append(chunk_id)
                except Exception as e:
                    logger.warning("Failed to delete chunk %s: %s", chunk_id, e)
            return deleted_ids

        deleted_ids = await asyncio.to_thread(_purge)
        logger.info("Purged %d RAG chunks for sourceUrn=%s", len(deleted_ids), source_urn)
        return len(deleted_ids), deleted_ids

    async def list_by_kind(
        self,
        client_id: str,
        project_id: str | None,
        kind: str,
        limit: int = 100,
    ) -> list[dict]:
        """List all chunks matching a specific kind, with tenant filtering."""
        def _query():
            collection = self.client.collections.get("KnowledgeChunk")

            # Build filter list — Weaviate TEXT fields can't filter by empty string
            parts = [wvq.Filter.by_property("kind").equal(kind)]
            if client_id:
                parts.append(wvq.Filter.by_property("clientId").equal(client_id))
            if project_id:
                parts.append(wvq.Filter.by_property("projectId").equal(project_id))

            filters = wvq.Filter.all_of(parts) if len(parts) > 1 else parts[0]

            response = collection.query.fetch_objects(
                filters=filters,
                limit=limit,
                return_properties=["content", "sourceUrn", "clientId", "projectId", "kind"],
            )

            results = []
            for obj in response.objects:
                results.append({
                    "content": obj.properties.get("content", ""),
                    "sourceUrn": obj.properties.get("sourceUrn", ""),
                    "metadata": obj.properties,
                })
            return results

        return await asyncio.to_thread(_query)

    async def retrieve(self, request: RetrievalRequest, embedding_priority: int | None = None) -> EvidencePack:
        logger.info(
            "RAG_READ: START query='%s' clientId=%s projectId=%s groupId=%s maxResults=%d priority=%s",
            request.query, request.clientId, request.projectId or "",
            request.groupId or "", request.maxResults, embedding_priority
        )

        vector = await self._embed_with_priority(request.query, priority=embedding_priority)
        logger.info("RAG_READ: EMBEDDED query vector_dim=%d", len(vector))

        def _weaviate_query():
            collection = self.client.collections.get("KnowledgeChunk")

            # Tenant filtering — Weaviate TEXT fields can't filter by empty string,
            # so we only add filters for non-empty values
            parts = []
            if request.clientId:
                parts.append(wvq.Filter.by_property("clientId").equal(request.clientId))
            if request.projectId:
                project_alternatives = [
                    wvq.Filter.by_property("projectId").equal(request.projectId),
                ]
                if request.groupId:
                    project_alternatives.append(
                        wvq.Filter.by_property("groupId").equal(request.groupId),
                    )
                parts.append(
                    wvq.Filter.any_of(project_alternatives) if len(project_alternatives) > 1
                    else project_alternatives[0]
                )

            filters = wvq.Filter.all_of(parts) if len(parts) > 1 else (parts[0] if parts else None)

            logger.info(
                "RAG_READ: QUERY filters: clientId=%s projectId=%s groupId=%s filter_parts=%d",
                request.clientId if request.clientId else "ANY",
                request.projectId if request.projectId else "ANY",
                request.groupId if request.groupId else "ANY",
                len(parts)
            )

            return collection.query.near_vector(
                near_vector=vector,
                limit=request.maxResults,
                filters=filters,
                return_metadata=wvq.MetadataQuery(distance=True)
            )

        response = await asyncio.to_thread(_weaviate_query)
        logger.info("RAG_READ: WEAVIATE returned %d results", len(response.objects))

        items = []
        for i, obj in enumerate(response.objects):
            items.append(EvidenceItem(
                content=obj.properties["content"],
                score=1.0 - (obj.metadata.distance or 0.0),
                sourceUrn=obj.properties["sourceUrn"],
                metadata=obj.properties
            ))
            if i < 3:  # Log first 3 results
                logger.info(
                    "RAG_READ: RESULT[%d] score=%.3f distance=%.3f sourceUrn=%s clientId=%s projectId=%s content_preview=%s",
                    i, 1.0 - (obj.metadata.distance or 0.0), obj.metadata.distance or 0.0,
                    obj.properties.get("sourceUrn", "?"),
                    obj.properties.get("clientId", "?"),
                    obj.properties.get("projectId", "?"),
                    obj.properties.get("content", "")[:100]
                )

        logger.info("RAG_READ: COMPLETE query='%s' results=%d", request.query, len(items))
        return EvidencePack(items=items)
