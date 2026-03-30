import asyncio
import logging
import uuid
import httpx

from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.core.config import settings
from app.db.weaviate import get_weaviate_client
from app.api.models import IngestRequest, RetrievalRequest, EvidenceItem, EvidencePack
import weaviate.classes.config as wvc
import weaviate.classes.query as wvq

logger = logging.getLogger(__name__)


class RagService:

    def __init__(self):
        # Default: no priority header (NORMAL). Callers pass explicit priority via header.
        self.embedding_priority = None
        self.http_client = httpx.AsyncClient(timeout=300.0)  # 5 min — embedding + router queue
        self.text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=2000,
            chunk_overlap=400
        )
        # Concurrency tuned per GPU-2 benchmark: sweet spot 4-5 concurrent.
        # With multi-worker uvicorn, total concurrent = UVICORN_WORKERS × this value.
        self._max_concurrent = settings.MAX_CONCURRENT_EMBEDDINGS
        self._embedding_semaphore = asyncio.Semaphore(self._max_concurrent)
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
                    # Content hash for idempotent re-ingest detection
                    wvc.Property(name="contentHash", data_type=wvc.DataType.TEXT),
                    # When the source content was observed/created (ISO format)
                    wvc.Property(name="observedAt", data_type=wvc.DataType.TEXT),
                ]
            )
        else:
            # Migration: add missing properties
            try:
                collection = self.client.collections.get("KnowledgeChunk")
                collection.config.add_property(
                    wvc.Property(name="contentHash", data_type=wvc.DataType.TEXT)
                )
                logger.info("Added contentHash property to KnowledgeChunk schema")
            except Exception:
                pass
            try:
                collection = self.client.collections.get("KnowledgeChunk")
                collection.config.add_property(
                    wvc.Property(name="observedAt", data_type=wvc.DataType.TEXT)
                )
                logger.info("Added observedAt property to KnowledgeChunk schema")
            except Exception:
                pass  # Already exists
            # Source credibility tier (SourceCredibility enum value)
            try:
                collection = self.client.collections.get("KnowledgeChunk")
                collection.config.add_property(
                    wvc.Property(name="credibility", data_type=wvc.DataType.TEXT)
                )
                logger.info("Added credibility property to KnowledgeChunk schema")
            except Exception:
                pass
            # Branch scope — which git branch this info applies to
            try:
                collection = self.client.collections.get("KnowledgeChunk")
                collection.config.add_property(
                    wvc.Property(name="branchScope", data_type=wvc.DataType.TEXT)
                )
                logger.info("Added branchScope property to KnowledgeChunk schema")
            except Exception:
                pass
            # Branch role — "default", "protected", "active", "merged", "stale"
            try:
                collection = self.client.collections.get("KnowledgeChunk")
                collection.config.add_property(
                    wvc.Property(name="branchRole", data_type=wvc.DataType.TEXT)
                )
                logger.info("Added branchRole property to KnowledgeChunk schema")
            except Exception:
                pass

    async def _embed_with_priority(self, text: str | list[str], priority: int | None = None) -> list[float] | list[list[float]]:
        """Embed text with priority header for router.

        Priority passed explicitly by callers (from X-Ollama-Priority header passthrough).
        If None, no header is sent (router defaults to NORMAL).

        Uses a semaphore (MAX_CONCURRENT_EMBEDDINGS=5) to prevent GPU starvation
        when many ingest requests arrive simultaneously.
        """
        is_batch = isinstance(text, list)
        prompt = text if is_batch else [text]

        # Use explicit priority if provided, otherwise fall back to default
        effective_priority = priority if priority is not None else self.embedding_priority

        url = f"{settings.OLLAMA_EMBEDDING_BASE_URL}/api/embed"
        payload = {
            "model": settings.EMBEDDING_MODEL,
            "input": prompt,
        }
        headers = {"X-Ollama-Priority": str(effective_priority)} if effective_priority is not None else {}

        logger.info("RAG: EMBEDDING model=%s priority=%s (explicit=%s default=%s) semaphore_free=%d/%d",
                    settings.EMBEDDING_MODEL, effective_priority, priority, self.embedding_priority,
                    self._embedding_semaphore._value, self._max_concurrent)

        max_retries = 2
        for attempt in range(1 + max_retries):
            try:
                async with self._embedding_semaphore:
                    resp = await self.http_client.post(url, json=payload, headers=headers)
                    resp.raise_for_status()
                    data = resp.json()
                    embeddings = data.get("embeddings", [])
                    return embeddings if is_batch else embeddings[0]
            except (httpx.ConnectError, httpx.RemoteProtocolError, httpx.HTTPStatusError, OSError) as e:
                if attempt < max_retries:
                    wait = 2 ** (attempt + 1)
                    logger.warning(
                        "Embedding failed (attempt %d/%d): %s, retrying in %ds",
                        attempt + 1, max_retries + 1, e, wait,
                    )
                    await asyncio.sleep(wait)
                else:
                    logger.error("Embedding failed after %d attempts: %s", max_retries + 1, e)
                    raise
            except Exception as e:
                logger.error("Embedding failed: %s", e)
                raise

    async def _generate_contextual_prefix(self, request: IngestRequest) -> str:
        """
        Generate a short contextual prefix for a document (Anthropic's Contextual Retrieval pattern).

        One LLM call per document (not per chunk). The prefix is prepended to every chunk
        before embedding, giving each chunk context about what document it belongs to.

        Returns empty string on failure (graceful degradation — chunks embedded without prefix).
        """
        from app.services.llm_router import llm_generate

        # Use first ~2000 chars as document sample (enough for context, not too expensive)
        doc_sample = request.content[:2000]
        if len(request.content) > 2000:
            doc_sample += f"\n[... {len(request.content) - 2000} more characters ...]"

        prompt = f"""/no_think
Generate a brief contextual description (2-3 sentences, max 100 words) for this document.
Include: what type of content it is, who/what it's about, and its purpose.
This context will be prepended to each chunk of this document to improve search retrieval.
Return ONLY the context text, no quotes or labels.

Source: {request.sourceUrn}
Kind: {request.kind or 'unknown'}

Document:
{doc_sample}
"""
        try:
            model = settings.CONTEXTUAL_PREFIX_MODEL or settings.LLM_MODEL
            result = await llm_generate(
                prompt=prompt,
                max_tier="NONE",
                model=model,
                num_ctx=4096,
                priority=4,  # Low priority — don't block critical work
                temperature=0,
                format_json=False,
            )
            # Clean up thinking tags
            if "<think>" in result:
                import re
                result = re.sub(r"<think>.*?</think>", "", result, flags=re.DOTALL).strip()
            # Limit to 150 words max
            words = result.split()
            if len(words) > 150:
                result = " ".join(words[:150])
            return result.strip()
        except Exception as e:
            logger.warning("CONTEXTUAL_PREFIX: Failed for sourceUrn=%s: %s (proceeding without prefix)",
                          request.sourceUrn, e)
            return ""

    async def ingest(
        self,
        request: IngestRequest,
        graph_refs: list[str] = None,
        embedding_priority: int | None = None,
        content_hash: str = "",
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

        # Contextual prefix: generate document-level context and prepend to each chunk
        # This significantly improves retrieval by giving each chunk context about the source.
        if settings.CONTEXTUAL_PREFIX_ENABLED and len(request.content) > 200:
            context_prefix = await self._generate_contextual_prefix(request)
            if context_prefix:
                chunks = [f"{context_prefix}\n\n{chunk}" for chunk in chunks]
                logger.info("RAG_WRITE: CONTEXTUAL_PREFIX sourceUrn=%s prefix_len=%d", request.sourceUrn, len(context_prefix))

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
                    observed_at_str = ""
                    if hasattr(request, "observedAt") and request.observedAt:
                        observed_at_str = request.observedAt.isoformat() if hasattr(request.observedAt, "isoformat") else str(request.observedAt)
                    props = {
                            "content": chunk,
                            "sourceUrn": request.sourceUrn,
                            "clientId": request.clientId,
                            "projectId": request.projectId or "",
                            "groupId": request.groupId or "",
                            "kind": request.kind or "",
                            "graphRefs": graph_refs or [],
                            "contentHash": content_hash,
                            "observedAt": observed_at_str,
                    }
                    # Source credibility & branch scope (optional)
                    if hasattr(request, "credibility") and request.credibility:
                        props["credibility"] = request.credibility.value if hasattr(request.credibility, "value") else str(request.credibility)
                    if hasattr(request, "branchScope") and request.branchScope:
                        props["branchScope"] = request.branchScope
                    if hasattr(request, "branchRole") and request.branchRole:
                        props["branchRole"] = request.branchRole
                    batch.add_object(
                        uuid=chunk_id,
                        properties=props,
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
        Fetch chunks by their UUIDs using Weaviate filter-based batch query.

        Uses a single query with UUID filter instead of N individual fetches.
        """
        if not chunk_ids:
            return []

        def _fetch():
            collection = self.client.collections.get("KnowledgeChunk")
            results = []

            # Batch fetch using filter on UUID list (much faster than N individual queries)
            BATCH_SIZE = 100
            for batch_start in range(0, len(chunk_ids), BATCH_SIZE):
                batch_ids = chunk_ids[batch_start:batch_start + BATCH_SIZE]
                try:
                    response = collection.query.fetch_objects(
                        filters=wvq.Filter.by_id().contains_any(batch_ids),
                        limit=BATCH_SIZE,
                        return_properties=["content", "sourceUrn", "graphRefs"],
                    )
                    for obj in response.objects:
                        results.append({
                            "id": str(obj.uuid),
                            "content": obj.properties.get("content", ""),
                            "sourceUrn": obj.properties.get("sourceUrn", ""),
                            "graphRefs": obj.properties.get("graphRefs", []),
                        })
                except Exception as e:
                    # Fallback to individual fetch if batch filter not supported
                    logger.warning("Batch chunk fetch failed, falling back to individual: %s", e)
                    for chunk_id in batch_ids:
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

    async def count_by_source(self, source_urn: str) -> int:
        """Count existing RAG chunks for a sourceUrn (fast existence check)."""
        def _count():
            collection = self.client.collections.get("KnowledgeChunk")
            response = collection.aggregate.over_all(
                filters=wvq.Filter.by_property("sourceUrn").equal(source_urn),
                total_count=True,
            )
            return response.total_count or 0

        return await asyncio.to_thread(_count)

    async def get_content_hash(self, source_urn: str) -> str | None:
        """Get contentHash from first chunk for a sourceUrn (idempotent re-ingest check)."""
        def _get_hash():
            collection = self.client.collections.get("KnowledgeChunk")
            response = collection.query.fetch_objects(
                filters=wvq.Filter.by_property("sourceUrn").equal(source_urn),
                limit=1,
                return_properties=["contentHash"],
            )
            if response.objects:
                return response.objects[0].properties.get("contentHash") or None
            return None

        return await asyncio.to_thread(_get_hash)

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
                return_properties=["content", "sourceUrn", "clientId", "projectId", "kind", "observedAt"],
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
                # Include client-scoped data only — global data (clientId="") cannot be
                # filtered with WORD tokenization (empty string = stopwords-only error).
                # Global data is included by omitting clientId filter entirely when needed.
                parts.append(
                    wvq.Filter.by_property("clientId").equal(request.clientId)
                )
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

        # Score boosting: docs/conventions get priority, newsletters get penalized
        _SCORE_BOOST = {
            "docs://": 0.15,           # Internal documentation = highest priority
            "user-knowledge:convention": 0.10,  # User conventions
            "user-knowledge:decision": 0.08,    # User decisions
            "user-knowledge:pattern": 0.05,     # Patterns
            "git:": 0.03,              # Code commits
            "meeting:": 0.0,           # Meetings = neutral
        }
        _NEWSLETTER_PENALTY = -0.3     # Newsletters pushed way down

        items = []
        for i, obj in enumerate(response.objects):
            raw_score = 1.0 - (obj.metadata.distance or 0.0)
            source_urn = obj.properties.get("sourceUrn", "")
            content = obj.properties.get("content", "")

            # Apply boost based on source type
            boost = 0.0
            for prefix, b in _SCORE_BOOST.items():
                if source_urn.startswith(prefix):
                    boost = b
                    break

            # Detect and penalize newsletters/spam
            content_lower = content[:500].lower()
            if any(kw in content_lower for kw in [
                "#wearealza", "unsubscribe", "odhlásit", "newsletter",
                "©", "privacy policy", "zasílání obchodních", "obchodní sdělení",
            ]):
                boost = _NEWSLETTER_PENALTY

            items.append(EvidenceItem(
                content=content,
                score=min(1.0, max(0.0, raw_score + boost)),
                sourceUrn=source_urn,
                credibility=obj.properties.get("credibility", None),
                branchScope=obj.properties.get("branchScope", None),
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

        # Re-sort by boosted score (docs/conventions float up, newsletters sink)
        items.sort(key=lambda x: x.score, reverse=True)

        logger.info("RAG_READ: COMPLETE query='%s' results=%d", request.query, len(items))
        return EvidencePack(items=items)

    async def maintenance_embedding_batch(self, client_id: str, cursor: str | None, batch_size: int) -> dict:
        """Check and re-embed chunks with outdated embeddings.

        Chunks created before the current embedding model version get re-embedded.
        Cursor is the last processed UUID string.
        """
        def _check():
            collection = self.client.collections.get("KnowledgeChunk")
            # Fetch batch of chunks for this client
            filters = wvq.Filter.by_property("clientId").equal(client_id)
            response = collection.query.fetch_objects(
                filters=filters,
                limit=batch_size,
                offset=0 if not cursor else None,
                return_properties=["clientId", "sourceUrn"],
                return_metadata=wvq.MetadataQuery(creation_time=True),
            )

            if not response.objects:
                return {"completed": True, "processed": 0, "findings": 0, "fixed": 0,
                        "totalEstimate": 0, "nextCursor": None}

            processed = len(response.objects)
            last_uuid = str(response.objects[-1].uuid) if response.objects else None
            completed = processed < batch_size

            # For now, just count — actual re-embedding requires GPU and is expensive
            # TODO: check embedding model version in metadata, re-embed if outdated
            return {"completed": completed, "processed": processed, "findings": 0,
                    "fixed": 0, "totalEstimate": 0,
                    "nextCursor": last_uuid if not completed else None}

        try:
            return await asyncio.to_thread(_check)
        except Exception as e:
            logger.warning("maintenance_embedding_batch failed: %s", e)
            return {"completed": True, "processed": 0, "findings": 0, "fixed": 0,
                    "totalEstimate": 0, "nextCursor": None}

    async def retag_project(self, source_project_id: str, target_project_id: str) -> int:
        """Migrate all Weaviate chunks from one projectId to another.

        Called during project merge.
        Returns number of updated chunks.
        """
        def _retag():
            collection = self.client.collections.get("KnowledgeChunk")
            updated = 0
            offset = 0
            batch_size = 200

            while True:
                response = collection.query.fetch_objects(
                    filters=wvq.Filter.by_property("projectId").equal(source_project_id),
                    limit=batch_size,
                    offset=offset,
                    return_properties=["projectId"],
                )
                if not response.objects:
                    break

                for obj in response.objects:
                    collection.data.update(
                        uuid=obj.uuid,
                        properties={"projectId": target_project_id},
                    )
                    updated += 1

                if len(response.objects) < batch_size:
                    break
                offset += batch_size

            return updated

        try:
            updated = await asyncio.to_thread(_retag)
            logger.info("retag_project (Weaviate): %s → %s updated=%d chunks",
                        source_project_id, target_project_id, updated)
            return updated
        except Exception as e:
            logger.warning("retag_project (Weaviate) failed: %s → %s error=%s",
                           source_project_id, target_project_id, e)
            return 0

    async def retag_group(self, project_id: str, new_group_id: str | None) -> int:
        """Update groupId on all Weaviate chunks for a project.

        Called when a project's group membership changes.
        Iterates over all chunks with matching projectId and updates groupId.
        """
        def _retag():
            collection = self.client.collections.get("KnowledgeChunk")
            updated = 0
            offset = 0
            batch_size = 200
            target_group = new_group_id or ""

            while True:
                response = collection.query.fetch_objects(
                    filters=wvq.Filter.by_property("projectId").equal(project_id),
                    limit=batch_size,
                    offset=offset,
                    return_properties=["groupId"],
                )
                if not response.objects:
                    break

                for obj in response.objects:
                    current = obj.properties.get("groupId", "")
                    if current != target_group:
                        collection.data.update(
                            uuid=obj.uuid,
                            properties={"groupId": target_group},
                        )
                        updated += 1

                if len(response.objects) < batch_size:
                    break
                offset += batch_size

            return updated

        try:
            updated = await asyncio.to_thread(_retag)
            logger.info("retag_group (Weaviate): projectId=%s newGroupId=%s updated=%d chunks", project_id, new_group_id, updated)
            return updated
        except Exception as e:
            logger.warning("retag_group (Weaviate) failed: projectId=%s error=%s", project_id, e)
            return 0
