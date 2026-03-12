import asyncio
import logging
import time
from datetime import datetime
from urllib.parse import urlparse, urlunparse

from app.api.models import (
    IngestRequest, IngestResult, RetrievalRequest, EvidencePack,
    TraversalRequest, GraphNode, CrawlRequest,
    FullIngestRequest, FullIngestResult, AttachmentResult,
    GitStructureIngestRequest, GitStructureIngestResult,
    GitCommitIngestRequest, GitCommitIngestResult,
    CpgIngestRequest, CpgIngestResult,
    JoernScanRequest, JoernScanResult,
    KbDocumentUploadRequest, KbDocumentDto,
)
from app.services.rag_service import RagService
from app.services.graph_service import GraphService
from app.services.hybrid_retriever import HybridRetriever
from app.services.clients.joern_client import JoernClient, JoernResultDto
from app.services.document_extractor import DocumentExtractor
from app.services.llm_extraction_queue import LLMExtractionQueue, ExtractionTask
from app.core.config import settings
from app.db.arango import get_arango_db
from app.metrics import (
    rag_ingest_total, rag_ingest_duration, rag_ingest_chunks,
    rag_query_total, rag_query_duration,
    graph_query_total, graph_query_duration,
    extraction_queue_depth,
)
from langchain_community.document_loaders import RecursiveUrlLoader
from langchain_ollama import ChatOllama

logger = logging.getLogger(__name__)


class KnowledgeService:
    def __init__(self, extraction_queue: LLMExtractionQueue = None):
        self.rag_service = RagService()
        self.graph_service = GraphService()
        self.hybrid_retriever = HybridRetriever(self.rag_service, self.graph_service)
        self.joern_client = JoernClient()
        self.document_extractor = DocumentExtractor()
        self.extraction_queue = extraction_queue  # Async LLM extraction queue
        self._arango_db = get_arango_db()
        self._ensure_crawl_schema()
        # LLM for ingest tasks (simple relevance check, complex summary)
        # Persistent HTTP client for progress callbacks to Kotlin server
        import httpx
        self._callback_http = httpx.AsyncClient(timeout=5.0) if settings.KOTLIN_SERVER_URL else None
        # Direct httpx for LLM calls with priority headers (ChatOllama can't set per-request headers)
        self._llm_http = httpx.AsyncClient(timeout=settings.LLM_CALL_TIMEOUT)
        # LLM for ingest tasks — explicit num_ctx prevents Ollama using small
        # default (often 2048), and timeout prevents indefinite hangs that block
        # the async callback to Kotlin (causing infinite INDEXING→retry loop).
        # Same pattern as orchestrator: TOTAL_CONTEXT_WINDOW=32768, RESPONSE_RESERVE, TOKEN_RATIO.
        self.ingest_llm_simple = ChatOllama(
            base_url=settings.OLLAMA_INGEST_BASE_URL,
            model=settings.INGEST_MODEL_SIMPLE,
            format="json",
            temperature=0,
            num_ctx=8192,  # Link relevance check — short prompts (~3k chars)
            timeout=settings.LLM_CALL_TIMEOUT,
        )
        self.ingest_llm_complex = ChatOllama(
            base_url=settings.OLLAMA_INGEST_BASE_URL,
            model=settings.INGEST_MODEL_COMPLEX,
            format="json",
            num_ctx=8192,  # Summary extraction — truncated input fits in 8k
            timeout=settings.LLM_CALL_TIMEOUT,
        )

    def _ensure_crawl_schema(self):
        """Ensure CrawledUrls collection exists for URL dedup tracking."""
        if not self._arango_db.has_collection("CrawledUrls"):
            col = self._arango_db.create_collection("CrawledUrls")
            col.add_hash_index(fields=["clientId", "normalizedUrl"], unique=True)

    @staticmethod
    def _guess_mime(filename: str) -> str:
        """Guess MIME type from filename extension."""
        ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
        _MIME_MAP = {
            "pdf": "application/pdf",
            "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xls": "application/vnd.ms-excel",
            "html": "text/html", "htm": "text/html",
            "txt": "text/plain", "csv": "text/csv", "md": "text/markdown",
            "json": "application/json", "xml": "application/xml",
            "yaml": "application/x-yaml", "yml": "application/x-yaml",
            "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg",
            "webp": "image/webp", "bmp": "image/bmp", "gif": "image/gif",
            "tiff": "image/tiff",
        }
        return _MIME_MAP.get(ext, "application/octet-stream")

    def _normalize_url(self, url: str) -> str:
        """Normalize URL for dedup: strip fragment, trailing slash, lowercase host."""
        parsed = urlparse(url)
        normalized = urlunparse((
            parsed.scheme.lower(),
            parsed.netloc.lower(),
            parsed.path.rstrip('/') or '/',
            parsed.params,
            parsed.query,
            '',  # strip fragment
        ))
        return normalized

    async def _is_url_indexed(self, client_id: str, url: str) -> bool:
        """Check if URL was already crawled and indexed."""
        normalized = self._normalize_url(url)

        def _check():
            col = self._arango_db.collection("CrawledUrls")
            cursor = col.find({"clientId": client_id, "normalizedUrl": normalized}, limit=1)
            return len(list(cursor)) > 0

        return await asyncio.to_thread(_check)

    async def _mark_url_indexed(self, client_id: str, url: str, depth: int):
        """Mark URL as crawled/indexed."""
        normalized = self._normalize_url(url)

        def _mark():
            col = self._arango_db.collection("CrawledUrls")
            try:
                col.insert({
                    "clientId": client_id,
                    "normalizedUrl": normalized,
                    "originalUrl": url,
                    "depth": depth,
                    "indexedAt": datetime.utcnow().isoformat(),
                })
            except Exception:
                # Already exists (unique index), update timestamp
                cursor = col.find({"clientId": client_id, "normalizedUrl": normalized}, limit=1)
                for doc in cursor:
                    col.update({"_key": doc["_key"], "indexedAt": datetime.utcnow().isoformat()})

        await asyncio.to_thread(_mark)

    async def ingest(self, request: IngestRequest, embedding_priority: int | None = None, content_hash: str = "") -> IngestResult:
        """
        Ingest content with async LLM extraction.

        Flow:
        1. RAG Ingest → embedding + Weaviate insert (fast, returns immediately)
        2. Enqueue LLM extraction task → background worker processes later
        3. Return immediately without waiting for LLM

        Queue has two priority levels (FIFO within each):
        - CRITICAL (priority 0): processed first, embedding runs on GPU
        - NORMAL (priority 4+): processed after all CRITICAL tasks
        """
        logger.info(
            "KB_WRITE: INGEST_START sourceUrn=%s kind=%s clientId=%s projectId=%s groupId=%s content_len=%d priority=%s",
            request.sourceUrn, request.kind or "?", request.clientId,
            request.projectId or "", request.groupId or "", len(request.content or ""), embedding_priority
        )

        # 1. RAG Ingest - get chunk IDs (fast)
        rag_start = time.time()
        chunks_count, chunk_ids = await self.rag_service.ingest(request, embedding_priority=embedding_priority, content_hash=content_hash)
        rag_ingest_duration.observe(time.time() - rag_start)
        rag_ingest_total.labels(status="success").inc()
        rag_ingest_chunks.observe(chunks_count)
        logger.info(
            "KB_WRITE: RAG_INGEST_DONE sourceUrn=%s chunks=%d chunk_ids_sample=%s",
            request.sourceUrn, chunks_count, chunk_ids[:3] if len(chunk_ids) > 3 else chunk_ids
        )

        # 2. Always enqueue LLM extraction (priority queue handles ordering: CRITICAL first, then NORMAL, FIFO within each)
        effective_priority = embedding_priority if embedding_priority is not None else 4

        if self.extraction_queue and chunk_ids:
            await self._enqueue_extraction(request, chunk_ids, effective_priority)
        elif chunk_ids:
            logger.warning(
                "KB_WRITE: NO_EXTRACTION_QUEUE sourceUrn=%s has_chunks=%s",
                request.sourceUrn, bool(chunk_ids)
            )

        logger.info(
            "KB_WRITE: INGEST_COMPLETE sourceUrn=%s chunks=%d priority=%d clientId=%s projectId=%s",
            request.sourceUrn, chunks_count, effective_priority,
            request.clientId, request.projectId or ""
        )

        return IngestResult(
            status="success",
            chunks_count=chunks_count,
            nodes_created=0,
            edges_created=0,
            chunk_ids=chunk_ids,
            entity_keys=[],
        )

    async def ingest_immediate(self, request: IngestRequest, embedding_priority: int | None = None) -> IngestResult:
        """Synchronous ingest — RAG + LLM extraction in one call.

        Unlike regular ingest() which queues LLM extraction for background
        processing, this method runs the full pipeline synchronously:
        1. RAG ingest (embedding + Weaviate)
        2. LLM entity extraction (graph nodes + edges)
        3. Bidirectional chunk↔entity linking

        Use for critical writes (affair parking, user memory) where the caller
        needs the data searchable immediately.
        """
        logger.info(
            "KB_WRITE: INGEST_IMMEDIATE_START sourceUrn=%s kind=%s clientId=%s content_len=%d",
            request.sourceUrn, request.kind or "?", request.clientId, len(request.content or ""),
        )

        # 1. RAG ingest (fast)
        rag_start = time.time()
        chunks_count, chunk_ids = await self.rag_service.ingest(request, embedding_priority=embedding_priority)
        rag_ingest_duration.observe(time.time() - rag_start)
        rag_ingest_total.labels(status="success").inc()
        rag_ingest_chunks.observe(chunks_count)

        nodes_created = 0
        edges_created = 0
        entity_keys: list[str] = []

        # 2. LLM entity extraction — synchronous (skip queue)
        if chunk_ids:
            try:
                nodes_created, edges_created, entity_keys = await self.graph_service.ingest(
                    request=request,
                    chunk_ids=chunk_ids,
                    embedding_priority=embedding_priority or 0,
                    max_tier=getattr(request, "maxTier", "NONE"),
                )

                # 3. Bidirectional chunk↔entity linking
                if entity_keys:
                    for chunk_id in chunk_ids:
                        try:
                            await self.rag_service.update_chunk_graph_refs(chunk_id, entity_keys)
                        except Exception as e:
                            logger.warning("Failed to link chunk %s to entities: %s", chunk_id, e)

            except Exception as e:
                logger.warning(
                    "KB_WRITE: INGEST_IMMEDIATE_EXTRACTION_FAILED sourceUrn=%s: %s",
                    request.sourceUrn, e,
                )

        logger.info(
            "KB_WRITE: INGEST_IMMEDIATE_COMPLETE sourceUrn=%s chunks=%d nodes=%d edges=%d entities=%d",
            request.sourceUrn, chunks_count, nodes_created, edges_created, len(entity_keys),
        )

        return IngestResult(
            status="success",
            chunks_count=chunks_count,
            nodes_created=nodes_created,
            edges_created=edges_created,
            chunk_ids=chunk_ids,
            entity_keys=entity_keys,
        )

    async def _enqueue_extraction(self, request: IngestRequest, chunk_ids: list[str], priority: int):
        """Enqueue LLM extraction task for background processing."""
        import uuid
        task = ExtractionTask(
            task_id=str(uuid.uuid4()),
            source_urn=request.sourceUrn,
            content=request.content,
            client_id=request.clientId,
            project_id=request.projectId,
            kind=request.kind,
            chunk_ids=chunk_ids,
            created_at=datetime.utcnow().isoformat(),
            priority=priority,
            max_tier=getattr(request, "maxTier", "NONE"),
        )
        await self.extraction_queue.enqueue(task)
        logger.info(
            "KB_WRITE: LLM_EXTRACTION_QUEUED sourceUrn=%s task_id=%s priority=%d",
            request.sourceUrn, task.task_id, priority
        )

    async def ingest_file(self, file_bytes: bytes, filename: str, request: IngestRequest) -> IngestResult:
        mime_type = self._guess_mime(filename)
        result = await self.document_extractor.extract(file_bytes, filename, mime_type)
        logger.info("DocumentExtractor: file=%s method=%s chars=%d", filename, result.method, len(result.text))

        request.content = result.text
        if result.method == "vlm":
            request.kind = "image"
        return await self.ingest(request)

    async def extract_text_only(self, file_bytes: bytes, filename: str, mime_type: str) -> dict:
        """Extract text from a file without RAG indexing.

        Uses DocumentExtractor (VLM-first for images, pymupdf for PDFs,
        python-docx/openpyxl for Office). No graph nodes or RAG chunks are created.

        Used by Qualifier relevance assessment pipeline.
        """
        try:
            result = await self.document_extractor.extract(file_bytes, filename, mime_type)
            logger.info("extract_text_only: file=%s method=%s chars=%d", filename, result.method, len(result.text))
            return {"extracted_text": result.text or "", "method": result.method}
        except Exception as e:
            logger.error("extract_text_only failed for %s: %s", filename, e)
            return {"extracted_text": "", "method": "error", "error": str(e)}

    async def analyze_code(self, query: str, workspace_path: str) -> JoernResultDto:
        return await self.joern_client.run(query, workspace_path)

    async def purge(self, source_urn: str) -> dict:
        """
        Purge all KB data for a given sourceUrn.

        1. Delete RAG chunks from Weaviate
        2. Remove chunk references from ArangoDB nodes/edges
        3. Delete orphaned nodes/edges
        """
        logger.info("Purge started sourceUrn=%s", source_urn)

        # 1. Delete RAG chunks
        chunks_deleted, deleted_ids = await self.rag_service.purge_by_source(source_urn)

        # 2. Clean graph references
        nodes_cleaned, edges_cleaned, nodes_deleted, edges_deleted = \
            await self.graph_service.purge_chunk_refs(deleted_ids)

        logger.info("Purge complete sourceUrn=%s chunks=%d nodes_cleaned=%d edges_cleaned=%d "
                     "nodes_deleted=%d edges_deleted=%d",
                     source_urn, chunks_deleted, nodes_cleaned, edges_cleaned,
                     nodes_deleted, edges_deleted)

        return {
            "chunks_deleted": chunks_deleted,
            "nodes_cleaned": nodes_cleaned,
            "edges_cleaned": edges_cleaned,
            "nodes_deleted": nodes_deleted,
            "edges_deleted": edges_deleted,
        }

    async def retrieve(self, request: RetrievalRequest, embedding_priority: int | None = None) -> EvidencePack:
        """
        Retrieve evidence using hybrid RAG + Graph approach.

        Uses the HybridRetriever for advanced retrieval with:
        - Vector similarity search (RAG)
        - Graph traversal expansion
        - Entity extraction from query
        - Reciprocal Rank Fusion (RRF) scoring
        - Source diversity
        """
        logger.info(
            "KB_READ: RETRIEVE_START query='%s' clientId=%s projectId=%s groupId=%s expandGraph=%s maxResults=%d priority=%s",
            request.query, request.clientId, request.projectId or "",
            request.groupId or "", request.expandGraph, request.maxResults, embedding_priority
        )

        query_start = time.time()
        try:
            result = await self.hybrid_retriever.retrieve(
                request,
                expand_graph=request.expandGraph,
                extract_entities=True,
                use_rrf=True,
                max_graph_hops=2,
                max_seeds=5,
                diversity_factor=0.7,
                embedding_priority=embedding_priority
            )
            rag_query_total.labels(status="success").inc()
            rag_query_duration.observe(time.time() - query_start)
        except Exception as e:
            rag_query_total.labels(status="error").inc()
            rag_query_duration.observe(time.time() - query_start)
            raise

        logger.info(
            "KB_READ: RETRIEVE_COMPLETE query='%s' results=%d",
            request.query, len(result.items)
        )
        return result

    async def retrieve_simple(self, request: RetrievalRequest, embedding_priority: int | None = None) -> EvidencePack:
        """
        Simple RAG-only retrieval without graph expansion.

        Use this for fast queries where graph context is not needed.
        """
        return await self.rag_service.retrieve(request, embedding_priority=embedding_priority)

    async def traverse(self, request: TraversalRequest) -> list[GraphNode]:
        traverse_start = time.time()
        try:
            result = await self.graph_service.traverse(request)
            graph_query_total.labels(operation="traverse", status="success").inc()
            graph_query_duration.labels(operation="traverse").observe(time.time() - traverse_start)
            return result
        except Exception as e:
            graph_query_total.labels(operation="traverse", status="error").inc()
            graph_query_duration.labels(operation="traverse").observe(time.time() - traverse_start)
            raise

    async def crawl(self, request: CrawlRequest) -> IngestResult:
        """
        Crawl and ingest web content with URL dedup and LLM relevance filtering.

        - Tracks already-indexed URLs to avoid re-downloading
        - Max depth default 2
        - LLM decides what's relevant for further indexing
        """
        def raw_extractor(html):
            return html

        loader = RecursiveUrlLoader(
            url=request.url,
            max_depth=request.maxDepth,
            extractor=raw_extractor,
            prevent_outside=not request.allowExternalDomains,
            use_async=True,
            timeout=10,
            check_response_status=True
        )

        # Run blocking load in thread pool
        docs = await asyncio.to_thread(loader.load)
        logger.info("Crawl fetched %d pages from %s (depth=%d)", len(docs), request.url, request.maxDepth)

        total_chunks = 0
        total_nodes = 0
        total_edges = 0
        skipped = 0

        for doc in docs:
            source_url = doc.metadata.get("source", request.url)
            depth = doc.metadata.get("depth", 0)

            # Skip already-indexed URLs
            if await self._is_url_indexed(request.clientId, source_url):
                skipped += 1
                continue

            try:
                html_result = await self.document_extractor.extract(
                    doc.page_content.encode('utf-8'), "page.html", "text/html",
                )
                text = html_result.text
            except Exception as e:
                logger.warning("HTML extraction failed for %s: %s", source_url, e)
                continue

            if not text or len(text.strip()) < 50:
                continue

            # LLM relevance check for sub-pages (depth > 0)
            if depth > 0:
                relevant = await self._check_link_relevance(text, request.url, source_url)
                if not relevant:
                    logger.info("Skipping irrelevant page: %s", source_url)
                    await self._mark_url_indexed(request.clientId, source_url, depth)
                    skipped += 1
                    continue

            ingest_req = IngestRequest(
                clientId=request.clientId,
                projectId=request.projectId,
                sourceUrn=source_url,
                kind="documentation",
                content=text,
                metadata=doc.metadata
            )

            res = await self.ingest(ingest_req)
            total_chunks += res.chunks_count
            total_nodes += res.nodes_created
            total_edges += res.edges_created

            await self._mark_url_indexed(request.clientId, source_url, depth)

        logger.info("Crawl complete url=%s pages=%d indexed=%d skipped=%d chunks=%d nodes=%d edges=%d",
                     request.url, len(docs), len(docs) - skipped, skipped,
                     total_chunks, total_nodes, total_edges)

        return IngestResult(
            status="success",
            chunks_count=total_chunks,
            nodes_created=total_nodes,
            edges_created=total_edges
        )

    async def _check_link_relevance(self, text: str, root_url: str, page_url: str,
                                     max_tier: str = "NONE") -> bool:
        """Let LLM decide if a sub-page is relevant for indexing.
        Routes through llm_router for OpenRouter support on FREE+ tiers."""
        from app.services import llm_router

        # Simple model uses 8k context; keep truncation conservative for speed
        max_chars = 3000
        truncated = text[:max_chars] if len(text) > max_chars else text
        prompt = f"""You are evaluating whether a web page is relevant for indexing in a knowledge base.

Root URL: {root_url}
Page URL: {page_url}

Page content (first 3000 chars):
{truncated}

Is this page relevant for a knowledge base? Relevant pages contain:
- Technical documentation, API docs, guides, tutorials
- Product information, feature descriptions
- Architecture decisions, design docs
- Bug reports, changelogs, release notes

NOT relevant:
- Login/auth pages, cookie policies, legal notices
- Navigation-only pages, empty pages, error pages
- Marketing fluff, pricing pages, testimonials
- Social media feeds, comment sections

Respond with JSON: {{"relevant": true/false, "reason": "brief reason"}}"""

        try:
            logger.info("Calling LLM for relevance check page=%s", page_url)
            response_text = await llm_router.llm_generate(
                prompt=prompt,
                max_tier=max_tier,
                model=settings.INGEST_MODEL_SIMPLE,
                num_ctx=8192,
            )
            import json
            result = json.loads(response_text)
            return result.get("relevant", True)
        except Exception:
            # On error, default to indexing
            return True

    async def ingest_full(
        self,
        request: FullIngestRequest,
        attachments: list[tuple[bytes, str]]  # List of (file_bytes, filename)
    ) -> FullIngestResult:
        """
        Full document ingestion with attachments.

        1. Process all attachments (vision for images, Tika for documents)
        2. Combine main content + attachment texts
        3. Index everything as a single unit
        4. Generate summary for routing
        """
        all_content_parts = []
        attachment_results = []

        logger.info("Full ingest started source=%s type=%s attachments=%d",
                     request.sourceUrn, request.sourceType or "?", len(attachments))

        # Add main content
        if request.content:
            all_content_parts.append(f"=== MAIN CONTENT ===\n{request.content}")

        # Process attachments via DocumentExtractor
        max_tier = getattr(request, "maxTier", "NONE")
        for file_bytes, filename in attachments:
            try:
                mime_type = self._guess_mime(filename)
                result = await self.document_extractor.extract(file_bytes, filename, mime_type, max_tier)
                text = result.text
                content_type = result.method

                all_content_parts.append(f"=== ATTACHMENT: {filename} ===\n{text}")
                attachment_results.append(AttachmentResult(
                    filename=filename,
                    status="success",
                    contentType=content_type,
                    extractedText=text[:500] if text else None,
                ))
            except Exception as e:
                logger.warning("Attachment processing failed file=%s: %s", filename, e)
                attachment_results.append(AttachmentResult(
                    filename=filename,
                    status="failed",
                    contentType="unknown",
                    error=str(e),
                ))

        # Combine all content
        combined_content = "\n\n".join(all_content_parts)

        # Create ingest request for combined content
        ingest_req = IngestRequest(
            clientId=request.clientId,
            projectId=request.projectId,
            sourceUrn=request.sourceUrn,
            kind=request.sourceType or "",
            content=combined_content,
            metadata={
                **request.metadata,
                "subject": request.subject,
                "sourceType": request.sourceType,
                "attachmentCount": len(attachments),
            },
            observedAt=request.observedAt,
            maxTier=getattr(request, "maxTier", "NONE"),
        )

        # Ingest to RAG — idempotent: skip if content unchanged, purge+re-ingest if changed
        import hashlib
        content_hash = hashlib.sha256(combined_content.encode()).hexdigest()[:16]

        existing_chunks = await self.rag_service.count_by_source(request.sourceUrn)
        if existing_chunks > 0:
            existing_hash = await self.rag_service.get_content_hash(request.sourceUrn)
            if existing_hash == content_hash:
                logger.info("Full ingest: sourceUrn=%s unchanged (hash=%s, %d chunks), skipping RAG",
                            request.sourceUrn, content_hash, existing_chunks)
                ingest_result = IngestResult(
                    status="success", chunks_count=existing_chunks,
                    nodes_created=0, edges_created=0,
                )
            else:
                logger.info("Full ingest: sourceUrn=%s content changed (old_hash=%s new_hash=%s), purge+re-ingest",
                            request.sourceUrn, existing_hash, content_hash)
                await self.rag_service.purge_by_source(request.sourceUrn)
                ingest_result = await self.ingest(ingest_req, content_hash=content_hash)
        else:
            ingest_result = await self.ingest(ingest_req, content_hash=content_hash)

        attachments_processed = sum(1 for r in attachment_results if r.status == "success")
        attachments_failed = sum(1 for r in attachment_results if r.status == "failed")

        # Generate summary with routing hints (30B model via router)
        summary_data = await self._generate_summary(
            content=combined_content,
            source_type=request.sourceType or "unknown",
            subject=request.subject,
            max_tier=getattr(request, "maxTier", "NONE"),
        )

        # Check assignment against client/project identity
        is_assigned = self._check_assignment(
            assigned_to=summary_data.get("assignedTo"),
            client_id=request.clientId or "",
            metadata=request.metadata or {},
        )

        logger.info("Full ingest complete source=%s chunks=%d "
                     "attachments_ok=%d attachments_fail=%d actionable=%s urgency=%s",
                     request.sourceUrn, ingest_result.chunks_count,
                     attachments_processed, attachments_failed,
                     summary_data.get("hasActionableContent", False),
                     summary_data.get("urgency", "normal"))

        return FullIngestResult(
            status="success",
            chunks_count=ingest_result.chunks_count,
            nodes_created=0,
            edges_created=0,
            attachments_processed=attachments_processed,
            attachments_failed=attachments_failed,
            summary=summary_data.get("summary", request.subject or "Processing..."),
            entities=summary_data.get("entities", []),
            hasActionableContent=summary_data.get("hasActionableContent", False),
            suggestedActions=summary_data.get("suggestedActions", []),
            hasFutureDeadline=summary_data.get("hasFutureDeadline", False),
            suggestedDeadline=summary_data.get("suggestedDeadline"),
            isAssignedToMe=is_assigned,
            urgency=summary_data.get("urgency", "normal"),
        )

    async def _post_progress_callback(
        self,
        callback_url: str,
        task_id: str,
        client_id: str,
        step: str,
        message: str,
        metadata: dict,
    ):
        """POST progress event to Kotlin server callback (fire-and-forget, non-blocking).
        Uses persistent HTTP client (_callback_http) for connection reuse."""
        if not self._callback_http:
            return
        try:
            await self._callback_http.post(callback_url, json={
                "taskId": task_id,
                "clientId": client_id,
                "step": step,
                "message": message,
                "metadata": metadata,
            })
        except Exception as e:
            logger.debug("KB progress callback failed: %s", e)

    async def _post_completion_callback(
        self,
        callback_url: str,
        task_id: str,
        client_id: str,
        status: str,
        result: dict | None = None,
        error: str | None = None,
    ):
        """POST completion/error event to Kotlin server when async processing finishes."""
        if not self._callback_http:
            logger.warning("No callback HTTP client — cannot notify server for task %s", task_id)
            return
        payload = {
            "taskId": task_id,
            "clientId": client_id,
            "status": status,
        }
        if result is not None:
            payload["result"] = result
        if error is not None:
            payload["error"] = error
        try:
            resp = await self._callback_http.post(callback_url, json=payload)
            if resp.status_code >= 400:
                logger.error("KB completion callback returned %d for task %s", resp.status_code, task_id)
        except Exception as e:
            logger.error("KB completion callback failed for task %s: %s", task_id, e)

    async def process_full_async(
        self,
        request: FullIngestRequest,
        attachments: list[tuple[bytes, str]],
        callback_url: str,
        task_id: str,
        client_id: str,
        embedding_priority: int | None = None,
    ):
        """Process full ingest in background and POST result to callback when done.

        Reuses ingest_full_streaming logic (RAG + LLM summary in parallel),
        fires progress callbacks along the way, then sends final result or error.
        KB handles retry internally — server just waits for callback.
        """
        import time as _time

        # Derive progress and completion URLs from the base callback URL
        # callback_url = "http://server:5500/internal/kb-done"
        # progress_url = "http://server:5500/internal/kb-progress"
        base = callback_url.rsplit("/internal/", 1)[0] if "/internal/" in callback_url else callback_url.rsplit("/", 1)[0]
        progress_url = f"{base}/internal/kb-progress"
        completion_url = callback_url  # /internal/kb-done

        t0 = _time.monotonic()
        logger.info("ASYNC_INGEST_START task=%s source=%s priority=%s", task_id, request.sourceUrn, embedding_priority)

        try:
            # Consume the streaming generator — progress callbacks fire via _post_progress_callback
            result_data = None
            async for event in self.ingest_full_streaming(
                request, attachments,
                callback_url=progress_url,
                task_id=task_id,
                client_id=client_id,
                embedding_priority=embedding_priority,
            ):
                if event.get("type") == "result":
                    result_data = event.get("data")

            elapsed = _time.monotonic() - t0

            if result_data:
                logger.info("ASYNC_INGEST_DONE task=%s elapsed=%.1fs", task_id, elapsed)
                await self._post_completion_callback(
                    completion_url, task_id, client_id,
                    status="done",
                    result=result_data,
                )
            else:
                logger.error("ASYNC_INGEST_NO_RESULT task=%s elapsed=%.1fs", task_id, elapsed)
                await self._post_completion_callback(
                    completion_url, task_id, client_id,
                    status="error",
                    error="No result produced by processing pipeline",
                )
        except Exception as e:
            elapsed = _time.monotonic() - t0
            logger.error("ASYNC_INGEST_FAILED task=%s elapsed=%.1fs error=%s",
                         task_id, elapsed, e, exc_info=True)
            await self._post_completion_callback(
                completion_url, task_id, client_id,
                status="error",
                error=f"{type(e).__name__}: {str(e)}",
            )

    async def ingest_full_streaming(
        self,
        request: FullIngestRequest,
        attachments: list[tuple[bytes, str]],
        callback_url: str = "",
        task_id: str = "",
        client_id: str = "",
        embedding_priority: int | None = None,
    ):
        """
        Streaming version of ingest_full that yields NDJSON progress events.

        Same logic as ingest_full but with:
        - Progress events at each major step
        - Parallel RAG + Summary via asyncio.gather()
        - Push-based progress callbacks via POST to Kotlin server (if callback_url provided)
        """
        import hashlib, time as _time

        all_content_parts = []
        attachment_results = []
        t0 = _time.monotonic()

        logger.info("Full ingest (streaming) started source=%s type=%s attachments=%d callback=%s",
                     request.sourceUrn, request.sourceType or "?", len(attachments), bool(callback_url))

        async def _emit(step, message, metadata=None):
            """Yield NDJSON event AND push to callback URL if configured."""
            if metadata is None:
                metadata = {}
            if callback_url and task_id:
                await self._post_progress_callback(callback_url, task_id, client_id, step, message, metadata)
            return {"type": "progress", "step": step, "message": message, "metadata": metadata}

        yield await _emit("start", "Zpracovávám obsah...")

        # ── 1. Process attachments ──
        if request.content:
            all_content_parts.append(f"=== MAIN CONTENT ===\n{request.content}")

        max_tier = getattr(request, "maxTier", "NONE")
        for file_bytes, filename in attachments:
            try:
                mime_type = self._guess_mime(filename)
                result = await self.document_extractor.extract(file_bytes, filename, mime_type, max_tier)
                text, content_type = result.text, result.method

                all_content_parts.append(f"=== ATTACHMENT: {filename} ===\n{text}")
                attachment_results.append(AttachmentResult(
                    filename=filename, status="success", contentType=content_type,
                    extractedText=text[:500] if text else None,
                ))
            except Exception as e:
                logger.warning("Attachment processing failed file=%s: %s", filename, e)
                attachment_results.append(AttachmentResult(
                    filename=filename, status="failed", contentType="unknown", error=str(e),
                ))

        attachments_processed = sum(1 for r in attachment_results if r.status == "success")
        attachments_failed = sum(1 for r in attachment_results if r.status == "failed")

        if attachments:
            yield await _emit("attachments",
                f"Zpracováno {attachments_processed}/{len(attachments)} příloh",
                {"processed": str(attachments_processed), "failed": str(attachments_failed)})

        # ── 2. Combine + hash ──
        combined_content = "\n\n".join(all_content_parts)
        content_hash = hashlib.sha256(combined_content.encode()).hexdigest()[:16]
        content_len = len(combined_content)

        yield await _emit("content_ready",
            f"Obsah připraven ({content_len:,} znaků)",
            {"content_length": str(content_len), "hash": content_hash})

        # ── 3. Idempotency check ──
        existing_chunks = await self.rag_service.count_by_source(request.sourceUrn)
        skip_rag = False
        rag_chunks_count = 0

        if existing_chunks > 0:
            existing_hash = await self.rag_service.get_content_hash(request.sourceUrn)
            if existing_hash == content_hash:
                skip_rag = True
                rag_chunks_count = existing_chunks
                yield await _emit("hash_match",
                    f"Obsah nezměněn ({existing_chunks} chunks), přeskakuji RAG",
                    {"chunks": str(existing_chunks), "hash": content_hash})

        ingest_req = IngestRequest(
            clientId=request.clientId,
            projectId=request.projectId,
            sourceUrn=request.sourceUrn,
            kind=request.sourceType or "",
            content=combined_content,
            metadata={
                **request.metadata,
                "subject": request.subject,
                "sourceType": request.sourceType,
                "attachmentCount": len(attachments),
            },
            observedAt=request.observedAt,
            maxTier=getattr(request, "maxTier", "NONE"),
        )

        if skip_rag:
            # Only run summary
            yield await _emit("llm_start", f"LLM analýza obsahu ({settings.INGEST_MODEL_COMPLEX})...")
            summary_data = await self._generate_summary(combined_content, request.sourceType or "unknown", request.subject, embedding_priority=embedding_priority, max_tier=getattr(request, "maxTier", "NONE"))
        else:
            if existing_chunks > 0:
                yield await _emit("purge", "Obsah změněn, mažu staré chunks...")
                await self.rag_service.purge_by_source(request.sourceUrn)

            # ── 4. Parallel RAG + Summary ──
            yield await _emit("rag_start", "Ukládám chunks do vektorové DB...")
            yield await _emit("llm_start", f"LLM analýza obsahu ({settings.INGEST_MODEL_COMPLEX})...")

            max_tier = getattr(request, "maxTier", "NONE")
            rag_task = asyncio.create_task(self.ingest(ingest_req, embedding_priority=embedding_priority, content_hash=content_hash))
            summary_task = asyncio.create_task(self._generate_summary(combined_content, request.sourceType or "unknown", request.subject, embedding_priority=embedding_priority, max_tier=max_tier))

            # Report each parallel task as it completes (real-time)
            ingest_result = None
            summary_data = None
            pending = {rag_task, summary_task}
            while pending:
                done, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    if task is rag_task:
                        ingest_result = task.result()
                        rag_chunks_count = ingest_result.chunks_count
                        yield await _emit("rag_done",
                            f"RAG uloženo: {rag_chunks_count} chunks, graf extrakce zařazena do fronty",
                            {"chunks": str(rag_chunks_count)})
                    elif task is summary_task:
                        summary_data = task.result()
                        yield await _emit("llm_done",
                            f"LLM hotovo: {summary_data.get('summary', '')[:80]}",
                            {
                                "entities": ", ".join(summary_data.get("entities", [])),
                                "actionable": str(summary_data.get("hasActionableContent", False)),
                            })

        # ── 5. Summary result (full metadata) ──
        yield await _emit("summary_done",
            f"Analýza: {summary_data.get('summary', '')[:100]}",
            {
                "summary": summary_data.get("summary", "")[:200],
                "entities": ", ".join(summary_data.get("entities", [])),
                "actionable": str(summary_data.get("hasActionableContent", False)),
                "urgency": summary_data.get("urgency", "normal"),
                "suggestedActions": ", ".join(summary_data.get("suggestedActions", [])),
                "hasFutureDeadline": str(summary_data.get("hasFutureDeadline", False)),
                "suggestedDeadline": summary_data.get("suggestedDeadline") or "",
                "assignedTo": summary_data.get("assignedTo") or "",
            })

        # ── 6. Assignment check ──
        is_assigned = self._check_assignment(
            assigned_to=summary_data.get("assignedTo"),
            client_id=request.clientId or "",
            metadata=request.metadata or {},
        )

        elapsed = _time.monotonic() - t0
        logger.info("Full ingest (streaming) complete source=%s chunks=%d elapsed=%.1fs",
                     request.sourceUrn, rag_chunks_count, elapsed)

        # ── 7. Final result ──
        result = FullIngestResult(
            status="success",
            chunks_count=rag_chunks_count,
            nodes_created=0,
            edges_created=0,
            attachments_processed=attachments_processed,
            attachments_failed=attachments_failed,
            summary=summary_data.get("summary", request.subject or "Processing..."),
            entities=summary_data.get("entities", []),
            hasActionableContent=summary_data.get("hasActionableContent", False),
            suggestedActions=summary_data.get("suggestedActions", []),
            hasFutureDeadline=summary_data.get("hasFutureDeadline", False),
            suggestedDeadline=summary_data.get("suggestedDeadline"),
            isAssignedToMe=is_assigned,
            urgency=summary_data.get("urgency", "normal"),
        )
        yield {"type": "result", "data": result.model_dump()}

    async def _generate_summary(
        self,
        content: str,
        source_type: str,
        subject: str = None,
        embedding_priority: int | None = None,
        max_tier: str = "NONE",
    ) -> dict:
        """
        Generate summary, detect actionable content, deadlines,
        and assignment using LLM.

        Context budget (same pattern as chat/orchestrator):
        - Total window: INGEST_CONTEXT_WINDOW (default 32768 tokens)
        - Prompt overhead: INGEST_PROMPT_RESERVE (instruction template)
        - Response reserve: INGEST_RESPONSE_RESERVE (JSON output)
        - Content budget: window - prompt - response
        """
        import json

        # Use complex model for accurate entity extraction (30B via router)
        llm = self.ingest_llm_complex

        # Token-aware truncation: 8k context - prompt reserve - response reserve
        content_budget_tokens = 8192 - settings.INGEST_PROMPT_RESERVE - settings.INGEST_RESPONSE_RESERVE
        # ~2.5 chars per token for Czech text
        max_content_chars = int(content_budget_tokens * settings.TOKEN_ESTIMATE_RATIO)
        truncated = content[:max_content_chars] if len(content) > max_content_chars else content
        if len(content) > max_content_chars:
            logger.info(
                "Summary content truncated: %d → %d chars (budget: %d tokens)",
                len(content), max_content_chars, content_budget_tokens,
            )

        prompt = f"""Analyzuj tento {source_type or 'obsah'} a vrať JSON odpověď.
DŮLEŽITÉ: Pole "summary" piš ČESKY (čeština).

Předmět: {subject or 'N/A'}

Obsah:
{truncated}

Odpověz POUZE validním JSON:
{{
    "summary": "2-3 věty česky shrnující hlavní body",
    "entities": ["seznam", "klíčových", "entit"],
    "hasActionableContent": true/false,
    "suggestedActions": ["action1", "action2"],
    "hasFutureDeadline": true/false,
    "suggestedDeadline": "ISO-8601 datetime nebo null",
    "assignedTo": "jméno osoby/týmu nebo null",
    "urgency": "urgent/normal/low"
}}

hasActionableContent je TRUE POUZE pokud:
- Osobní email/zpráva která explicitně žádá MĚ o akci nebo odpověď
- Úkol/issue přiřazený konkrétní osobě
- Code review request směřovaný na mě
- Přímá otázka vyžadující MOJI odpověď
- Akční bod ze schůzky přiřazený někomu

hasActionableContent je FALSE pro:
- Newslettery, oznámení, marketingové emaily, pozvánky na události
- Automatické systémové notifikace (JIRA přechody, CI/CD, monitoring)
- Informační emaily nevyžadující odpověď
- Hromadné emaily, mailing listy, propagační obsah
- Oznámení o svátcích, firemní oznámení
- Automatické potvrzení, doručenky, notifikace o zásilkách

hasFutureDeadline je TRUE pokud:
- Obsah zmiňuje konkrétní budoucí deadline nebo termín
- Konec sprintu, milník, release date
- "do pátku", "termín 15. března", "deadline: 2025-04-01", apod.

suggestedDeadline: Pokud hasFutureDeadline je true, uveď deadline jako ISO-8601 (např. "2025-04-01T00:00:00"). Pokud nejasné, nastav null.

assignedTo: Extrahuj osobu/tým kterému je úkol přiřazen. Hledej "assigned to X", "assignee: X", "@mentions". Nastav null pokud nenalezeno.

urgency:
- "urgent" pokud označeno jako urgent/critical/blocker/P0, nebo má blízký deadline (<24h)
- "normal" pro běžné úkoly, standardní prioritu
- "low" pro nice-to-have, nízkou prioritu, informační obsah

suggestedActions příklady: "reply_email", "review_code", "fix_issue", "answer_question", "schedule_meeting", "pick_up_order"

Odpověz POUZE validním JSON."""

        try:
            from app.services.llm_router import llm_generate
            logger.info("Calling LLM for summary generation source_type=%s priority=%s max_tier=%s",
                        source_type, embedding_priority, max_tier)
            raw_content = await llm_generate(
                prompt=prompt,
                max_tier=max_tier,
                model=settings.INGEST_MODEL_COMPLEX,
                num_ctx=8192,
                priority=embedding_priority,
                temperature=0,
                format_json=True,
            )
            result = json.loads(raw_content)
            logger.info("Summary generated entities=%d actionable=%s deadline=%s urgency=%s",
                        len(result.get("entities", [])),
                        result.get("hasActionableContent", False),
                        result.get("hasFutureDeadline", False),
                        result.get("urgency", "normal"))
            return {
                "summary": result.get("summary", "No summary available"),
                "entities": result.get("entities", []),
                "hasActionableContent": result.get("hasActionableContent", False),
                "suggestedActions": result.get("suggestedActions", []),
                "hasFutureDeadline": result.get("hasFutureDeadline", False),
                "suggestedDeadline": result.get("suggestedDeadline"),
                "assignedTo": result.get("assignedTo"),
                "urgency": result.get("urgency", "normal"),
            }
        except Exception as e:
            logger.warning("Summary generation failed: %s", e)
            return {
                "summary": f"Content from {source_type}: {subject or 'No subject'}",
                "entities": [],
                "hasActionableContent": False,
                "suggestedActions": [],
                "hasFutureDeadline": False,
                "suggestedDeadline": None,
                "assignedTo": None,
                "urgency": "normal",
            }

    @staticmethod
    def _check_assignment(
        assigned_to: str | None,
        client_id: str,
        metadata: dict,
    ) -> bool:
        """Check if the LLM-extracted assignedTo matches the owning client/project.

        Compares the extracted assignee against:
        - clientId (often the team/org name)
        - metadata fields: clientName, projectName, teamName
        """
        if not assigned_to:
            return False
        assigned_lower = assigned_to.lower().strip()
        if not assigned_lower:
            return False

        candidates = {client_id.lower()} if client_id else set()
        for field in ("clientName", "projectName", "teamName"):
            val = metadata.get(field, "")
            if val:
                candidates.add(val.lower())

        return any(c and c in assigned_lower for c in candidates)

    async def ingest_git_structure(
        self,
        request: GitStructureIngestRequest,
    ) -> GitStructureIngestResult:
        """Ingest repository structure directly into graph (no LLM).

        Creates repository, branch, file, class, and method nodes with edges.
        When fileContents are provided, invokes tree-sitter for richer extraction.
        Called from Kotlin GitContinuousIndexer via RPC.
        """
        logger.info(
            "Git structure ingest started repo=%s branch=%s files=%d classes=%d file_contents=%d",
            request.repositoryIdentifier, request.branch,
            len(request.files), len(request.classes), len(request.fileContents),
        )

        result = await self.graph_service.ingest_git_structure(
            client_id=request.clientId,
            project_id=request.projectId,
            repo_identifier=request.repositoryIdentifier,
            branch=request.branch,
            default_branch=request.defaultBranch,
            branches=[b.model_dump() for b in request.branches],
            files=[f.model_dump() for f in request.files],
            classes=[c.model_dump() for c in request.classes],
            file_contents=[fc.model_dump() for fc in request.fileContents],
        )

        return GitStructureIngestResult(
            status="success",
            nodes_created=result["nodes_created"],
            edges_created=result["edges_created"],
            nodes_updated=result["nodes_updated"],
            repository_key=result["repository_key"],
            branch_key=result["branch_key"],
            files_indexed=result["files_indexed"],
            classes_indexed=result["classes_indexed"],
            methods_indexed=result.get("methods_indexed", 0),
        )

    async def ingest_git_commits(
        self,
        request: GitCommitIngestRequest,
    ) -> GitCommitIngestResult:
        """Ingest structured git commit data into graph + optional RAG.

        Creates commit nodes with edges to branch and file nodes.
        If diff_content is provided, also indexes it as RAG chunks.
        """
        logger.info(
            "Git commit ingest started repo=%s branch=%s commits=%d has_diff=%s",
            request.repositoryIdentifier, request.branch,
            len(request.commits), bool(request.diff_content),
        )

        # 1. Create graph nodes/edges for commits
        result = await self.graph_service.ingest_git_commits(
            client_id=request.clientId,
            project_id=request.projectId,
            repo_identifier=request.repositoryIdentifier,
            branch=request.branch,
            commits=[c.model_dump() for c in request.commits],
        )

        # 2. If diff content provided, ingest to RAG for fulltext search
        rag_chunks = 0
        if request.diff_content:
            commit_hashes = ",".join(c.hash[:8] for c in request.commits[:5])
            source_urn = f"git:commits:{request.repositoryIdentifier}:{commit_hashes}"
            ingest_req = IngestRequest(
                clientId=request.clientId,
                projectId=request.projectId,
                sourceUrn=source_urn,
                kind="git_commit",
                content=request.diff_content,
            )
            rag_result = await self.ingest(ingest_req, embedding_priority=4)
            rag_chunks = rag_result.chunks_count

        logger.info(
            "Git commit ingest complete repo=%s branch=%s commits=%d "
            "nodes=%d edges=%d rag_chunks=%d",
            request.repositoryIdentifier, request.branch,
            result["commits_ingested"], result["nodes_created"],
            result["edges_created"], rag_chunks,
        )

        return GitCommitIngestResult(
            status="success",
            commits_ingested=result["commits_ingested"],
            nodes_created=result["nodes_created"],
            edges_created=result["edges_created"],
            rag_chunks=rag_chunks,
        )

    async def ingest_cpg(
        self,
        request: CpgIngestRequest,
    ) -> CpgIngestResult:
        """Run Joern CPG export and import semantic edges into graph.

        Orchestrates the full CPG pipeline:
        1. Dispatch Joern K8s Job (or local subprocess) to generate CPG
        2. Read pruned CPG export JSON
        3. Import semantic edges (calls, extends, uses_type) into ArangoDB

        Called from Kotlin GitContinuousIndexer after structural ingest completes.
        """
        logger.info(
            "CPG ingest started project=%s branch=%s workspace=%s",
            request.projectId, request.branch, request.workspacePath,
        )

        # 1. Run Joern CPG export (K8s Job or local subprocess)
        cpg_data = await self.joern_client.run_cpg_export(request.workspacePath)

        logger.info(
            "CPG export complete: methods=%d types=%d calls=%d typeRefs=%d",
            len(cpg_data.get("methods", [])),
            len(cpg_data.get("types", [])),
            len(cpg_data.get("calls", [])),
            len(cpg_data.get("typeRefs", [])),
        )

        # 2. Import into ArangoDB graph
        result = await self.graph_service.ingest_cpg_export(
            client_id=request.clientId,
            project_id=request.projectId,
            branch=request.branch,
            cpg_data=cpg_data,
        )

        logger.info(
            "CPG ingest complete project=%s branch=%s "
            "methods_enriched=%d extends=%d calls=%d uses_type=%d",
            request.projectId, request.branch,
            result.get("methods_enriched", 0),
            result.get("extends_edges", 0),
            result.get("calls_edges", 0),
            result.get("uses_type_edges", 0),
        )

        return CpgIngestResult(
            status="success",
            methods_enriched=result.get("methods_enriched", 0),
            extends_edges=result.get("extends_edges", 0),
            calls_edges=result.get("calls_edges", 0),
            uses_type_edges=result.get("uses_type_edges", 0),
        )

    async def run_joern_scan(self, request: JoernScanRequest) -> JoernScanResult:
        """Run a pre-built Joern code analysis scan.

        Uses Joern K8s Job to analyze code for security issues, dataflow,
        callgraph, or complexity metrics.

        Args:
            request: Joern scan request with scanType and workspace path

        Returns:
            JoernScanResult with scan findings
        """
        # Query templates (same as MCP server)
        QUERY_TEMPLATES = {
            "security": """
@main def exec(inputPath: String) = {
  importCode(inputPath)
  val findings = scala.collection.mutable.ListBuffer[String]()

  // SQL injection: string concatenation in SQL-like calls
  cpg.call.name(".*(?i)(query|execute|prepare).*")
    .argument.isCallTo("<operator>.addition")
    .l.foreach { c =>
      findings += s"Potential SQL injection at ${c.location.filename}:${c.location.lineNumber}"
    }

  // Command injection: shell exec with dynamic input
  cpg.call.name(".*(?i)(exec|system|popen|runtime\\\\.exec).*")
    .l.foreach { c =>
      findings += s"Potential command injection at ${c.location.filename}:${c.location.lineNumber}"
    }

  // Hardcoded secrets: assignments with password/secret/key in name
  cpg.assignment.target
    .isIdentifier.name(".*(?i)(password|secret|api_?key|token).*")
    .l.foreach { i =>
      findings += s"Possible hardcoded secret '${i.name}' at ${i.location.filename}:${i.location.lineNumber}"
    }

  println(findings.mkString("\\n"))
}
""",
            "dataflow": """
@main def exec(inputPath: String) = {
  importCode(inputPath)
  val findings = scala.collection.mutable.ListBuffer[String]()

  // Identify HTTP request parameters (sources)
  val sources = cpg.parameter
    .name(".*(?i)(request|req|input|param|body|query).*")
    .l

  // Identify sensitive sinks
  val sinks = cpg.call
    .name(".*(?i)(query|execute|write|send|log|print).*")
    .l

  findings += s"Sources (HTTP inputs): ${sources.size}"
  findings += s"Sinks (sensitive operations): ${sinks.size}"

  sources.take(20).foreach { s =>
    findings += s"  Source: ${s.name} at ${s.location.filename}:${s.location.lineNumber}"
  }
  sinks.take(20).foreach { s =>
    findings += s"  Sink: ${s.name} at ${s.location.filename}:${s.location.lineNumber}"
  }

  println(findings.mkString("\\n"))
}
""",
            "callgraph": """
@main def exec(inputPath: String) = {
  importCode(inputPath)
  val findings = scala.collection.mutable.ListBuffer[String]()

  // Method summary
  val methods = cpg.method.nameNot("<.*>").l
  findings += s"Total methods: ${methods.size}"

  // Find methods with highest fan-out (most callees)
  methods.sortBy(-_.callOut.size).take(20).foreach { m =>
    val callees = m.callOut.callee.name.l.distinct
    if (callees.nonEmpty) {
      findings += s"${m.fullName} (${m.location.filename}:${m.location.lineNumber}) -> calls ${callees.size} methods: ${callees.take(5).mkString(", ")}${if (callees.size > 5) "..." else ""}"
    }
  }

  // Find uncalled methods (potentially dead code)
  val calledMethods = cpg.call.callee.fullName.toSet
  val uncalled = methods.filterNot(m => calledMethods.contains(m.fullName))
    .filterNot(_.name.matches(".*(?i)(main|init|constructor|test).*"))
  findings += s"Potentially uncalled methods: ${uncalled.size}"
  uncalled.take(10).foreach { m =>
    findings += s"  Dead? ${m.fullName} at ${m.location.filename}:${m.location.lineNumber}"
  }

  println(findings.mkString("\\n"))
}
""",
            "complexity": """
@main def exec(inputPath: String) = {
  importCode(inputPath)
  val findings = scala.collection.mutable.ListBuffer[String]()

  // Methods sorted by cyclomatic complexity (approximated by control structures)
  cpg.method.nameNot("<.*>").l
    .map { m =>
      val controlNodes = m.ast.isControlStructure.size
      (m, controlNodes)
    }
    .sortBy(-_._2)
    .take(20)
    .foreach { case (m, complexity) =>
      if (complexity > 0) {
        findings += s"Complexity ${complexity}: ${m.fullName} at ${m.location.filename}:${m.location.lineNumber} (${m.lineNumberEnd.getOrElse(0).asInstanceOf[Int] - m.lineNumber.getOrElse(0).asInstanceOf[Int]} lines)"
      }
    }

  // Long methods (>50 lines)
  cpg.method.nameNot("<.*>").l
    .filter { m =>
      val lines = m.lineNumberEnd.getOrElse(0).asInstanceOf[Int] - m.lineNumber.getOrElse(0).asInstanceOf[Int]
      lines > 50
    }
    .sortBy(m => -(m.lineNumberEnd.getOrElse(0).asInstanceOf[Int] - m.lineNumber.getOrElse(0).asInstanceOf[Int]))
    .take(10)
    .foreach { m =>
      val lines = m.lineNumberEnd.getOrElse(0).asInstanceOf[Int] - m.lineNumber.getOrElse(0).asInstanceOf[Int]
      findings += s"Long method (${lines} lines): ${m.fullName} at ${m.location.filename}:${m.location.lineNumber}"
    }

  println(findings.mkString("\\n"))
}
""",
        }

        query = QUERY_TEMPLATES.get(request.scanType)
        if not query:
            return JoernScanResult(
                status="error",
                scanType=request.scanType,
                output="",
                warnings=f"Unknown scan type: {request.scanType}",
                exitCode=1,
            )

        try:
            # Run Joern via K8s Job
            result = await self.joern_client.run(query, request.workspacePath)

            return JoernScanResult(
                status="success" if result.exitCode == 0 else "error",
                scanType=request.scanType,
                output=result.stdout or "",
                warnings=result.stderr,
                exitCode=result.exitCode,
            )
        except Exception as e:
            logger.error(f"Joern scan failed: {e}")
            return JoernScanResult(
                status="error",
                scanType=request.scanType,
                output="",
                warnings=str(e),
                exitCode=1,
            )

    # -----------------------------------------------------------------------
    # KB Document Upload & Management
    # -----------------------------------------------------------------------

    async def upload_kb_document(
        self,
        request: KbDocumentUploadRequest,
        file_bytes: bytes | None = None,
    ) -> KbDocumentDto:
        """Register an uploaded document in KB and trigger content extraction.

        The binary file is already stored on the shared FS by the Kotlin server.
        This method:
        1. Creates a kb_document node in ArangoDB.
        2. If file_bytes provided, extracts text via Tika and ingests into RAG.
        3. Updates the node with extraction results.

        Args:
            request: Upload metadata.
            file_bytes: Optional binary content for text extraction.
                        If None, the Kotlin server must trigger extraction separately.

        Returns:
            KbDocumentDto with node details.
        """
        import uuid
        doc_id = str(uuid.uuid4())
        source_urn = f"doc::id:{doc_id}"

        # 1. Create the graph node
        node = await self.graph_service.create_kb_document_node(
            doc_id=doc_id,
            client_id=request.clientId,
            project_id=request.projectId,
            filename=request.filename,
            mime_type=request.mimeType,
            size_bytes=request.sizeBytes,
            storage_path=request.storagePath,
            source_urn=source_urn,
            title=request.title,
            description=request.description,
            category=request.category.value if hasattr(request.category, 'value') else request.category,
            tags=request.tags,
            content_hash=request.contentHash,
        )

        # 2. If binary data provided, extract and ingest
        if file_bytes:
            try:
                await self._extract_and_ingest_document(
                    doc_id=doc_id,
                    file_bytes=file_bytes,
                    filename=request.filename,
                    source_urn=source_urn,
                    client_id=request.clientId,
                    project_id=request.projectId,
                )
            except Exception as e:
                logger.error("Document extraction failed doc_id=%s: %s", doc_id, e)
                await self.graph_service.update_kb_document_node(doc_id, {
                    "state": "FAILED",
                    "errorMessage": str(e)[:500],
                })

        return self._node_to_dto(node)

    async def _extract_and_ingest_document(
        self,
        doc_id: str,
        file_bytes: bytes,
        filename: str,
        source_urn: str,
        client_id: str,
        project_id: str | None,
    ):
        """Extract text from file and ingest into RAG + graph.

        Updates the kb_document node with extraction results.
        """
        # Extract text via DocumentExtractor
        mime_type = self._guess_mime(filename)
        result = await self.document_extractor.extract(file_bytes, filename, mime_type)
        text = result.text
        kind = "image" if result.method == "vlm" else "document"

        if not text:
            raise ValueError(f"No text could be extracted from {filename}")

        # Update node with extraction status
        preview = text[:500].strip() if text else None
        await self.graph_service.update_kb_document_node(doc_id, {
            "state": "EXTRACTED",
            "extractedTextPreview": preview,
        })

        # Ingest into RAG (creates chunks + links to graph nodes)
        ingest_request = IngestRequest(
            clientId=client_id,
            projectId=project_id,
            sourceUrn=source_urn,
            kind=kind,
            content=text,
            metadata={"filename": filename, "docId": doc_id},
        )
        result = await self.ingest(ingest_request)

        # Update node with RAG chunk links and mark as indexed
        updates = {
            "state": "INDEXED",
            "ragChunks": result.chunk_ids,
            "indexedAt": datetime.utcnow().isoformat(),
        }
        await self.graph_service.update_kb_document_node(doc_id, updates)

        logger.info(
            "KB document indexed doc_id=%s filename=%s chunks=%d",
            doc_id, filename, result.chunks_count,
        )

    async def list_kb_documents(
        self,
        client_id: str,
        project_id: str | None = None,
    ) -> list[KbDocumentDto]:
        """List all KB documents for a client."""
        nodes = await self.graph_service.list_kb_document_nodes(client_id, project_id)
        return [self._node_to_dto(n) for n in nodes]

    async def get_kb_document(self, doc_id: str) -> KbDocumentDto | None:
        """Get a single KB document by ID."""
        node = await self.graph_service.get_kb_document_node(doc_id)
        if not node:
            return None
        return self._node_to_dto(node)

    async def update_kb_document(
        self,
        doc_id: str,
        title: str | None = None,
        description: str | None = None,
        category: str | None = None,
        tags: list[str] | None = None,
    ) -> KbDocumentDto | None:
        """Update document metadata."""
        updates = {}
        if title is not None:
            updates["title"] = title
            updates["label"] = title
        if description is not None:
            updates["description"] = description
        if category is not None:
            updates["category"] = category
        if tags is not None:
            updates["tags"] = tags

        if not updates:
            node = await self.graph_service.get_kb_document_node(doc_id)
        else:
            node = await self.graph_service.update_kb_document_node(doc_id, updates)

        if not node:
            return None
        return self._node_to_dto(node)

    async def delete_kb_document(self, doc_id: str) -> bool:
        """Delete a KB document: purge RAG chunks, remove graph node."""
        node = await self.graph_service.get_kb_document_node(doc_id)
        if not node:
            return False

        source_urn = node.get("sourceUrn", "")
        if source_urn:
            try:
                await self.purge(source_urn)
            except Exception as e:
                logger.warning("Failed to purge RAG for doc %s: %s", doc_id, e)

        return await self.graph_service.delete_kb_document_node(doc_id)

    async def reindex_kb_document(
        self,
        doc_id: str,
        file_bytes: bytes,
    ) -> bool:
        """Re-extract and re-ingest a document's content."""
        node = await self.graph_service.get_kb_document_node(doc_id)
        if not node:
            return False

        # Purge old RAG data
        source_urn = node.get("sourceUrn", "")
        if source_urn:
            try:
                await self.purge(source_urn)
            except Exception:
                pass

        # Re-extract and ingest
        try:
            await self._extract_and_ingest_document(
                doc_id=doc_id,
                file_bytes=file_bytes,
                filename=node["filename"],
                source_urn=source_urn,
                client_id=node["clientId"],
                project_id=node.get("projectId"),
            )
            return True
        except Exception as e:
            logger.error("Reindex failed doc_id=%s: %s", doc_id, e)
            await self.graph_service.update_kb_document_node(doc_id, {
                "state": "FAILED",
                "errorMessage": str(e)[:500],
            })
            return False

    @staticmethod
    def _node_to_dto(node: dict) -> KbDocumentDto:
        """Convert ArangoDB node dict to KbDocumentDto."""
        return KbDocumentDto(
            id=node.get("docId", node.get("_key", "")),
            clientId=node.get("clientId", ""),
            projectId=node.get("projectId") or None,
            filename=node.get("filename", ""),
            mimeType=node.get("mimeType", ""),
            sizeBytes=node.get("sizeBytes", 0),
            storagePath=node.get("storagePath", ""),
            state=node.get("state", "UPLOADED"),
            category=node.get("category", "OTHER"),
            title=node.get("title"),
            description=node.get("description"),
            tags=node.get("tags", []),
            extractedTextPreview=node.get("extractedTextPreview"),
            pageCount=node.get("pageCount"),
            contentHash=node.get("contentHash"),
            sourceUrn=node.get("sourceUrn", ""),
            errorMessage=node.get("errorMessage"),
            ragChunks=node.get("ragChunks", []),
            uploadedAt=node.get("uploadedAt", ""),
            indexedAt=node.get("indexedAt"),
        )

