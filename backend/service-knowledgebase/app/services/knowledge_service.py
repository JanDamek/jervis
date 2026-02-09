import asyncio
import logging
from datetime import datetime
from urllib.parse import urlparse, urlunparse

from app.api.models import (
    IngestRequest, IngestResult, RetrievalRequest, EvidencePack,
    TraversalRequest, GraphNode, CrawlRequest,
    FullIngestRequest, FullIngestResult, AttachmentResult
)
from app.services.rag_service import RagService
from app.services.graph_service import GraphService
from app.services.hybrid_retriever import HybridRetriever
from app.services.clients.tika_client import TikaClient
from app.services.clients.joern_client import JoernClient, JoernResultDto
from app.services.image_service import ImageService
from app.core.config import settings
from app.db.arango import get_arango_db
from langchain_community.document_loaders import RecursiveUrlLoader

logger = logging.getLogger(__name__)


class KnowledgeService:
    def __init__(self):
        self.rag_service = RagService()
        self.graph_service = GraphService()
        self.hybrid_retriever = HybridRetriever(self.rag_service, self.graph_service)
        self.tika_client = TikaClient()
        self.joern_client = JoernClient()
        self.image_service = ImageService()
        self._arango_db = get_arango_db()
        self._ensure_crawl_schema()

    def _ensure_crawl_schema(self):
        """Ensure CrawledUrls collection exists for URL dedup tracking."""
        if not self._arango_db.has_collection("CrawledUrls"):
            col = self._arango_db.create_collection("CrawledUrls")
            col.add_hash_index(fields=["clientId", "normalizedUrl"], unique=True)

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
        col = self._arango_db.collection("CrawledUrls")
        cursor = col.find({"clientId": client_id, "normalizedUrl": normalized}, limit=1)
        return len(list(cursor)) > 0

    async def _mark_url_indexed(self, client_id: str, url: str, depth: int):
        """Mark URL as crawled/indexed."""
        normalized = self._normalize_url(url)
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

    async def ingest(self, request: IngestRequest) -> IngestResult:
        """
        Ingest content with bidirectional linking between RAG and Graph.

        Flow:
        1. RAG Ingest → get chunk IDs
        2. Graph Ingest with chunk IDs → get entity keys (nodes link to chunks)
        3. Update RAG chunks with entity keys (chunks link to nodes)
        """
        logger.info("Ingest started  source=%s kind=%s content_len=%d",
                     request.sourceUrn, request.kind or "?", len(request.content or ""))

        # 1. RAG Ingest - get chunk IDs
        chunks_count, chunk_ids = await self.rag_service.ingest(request)

        # 2. Graph Ingest - pass chunk IDs for bidirectional linking
        #    Graph nodes will store ragChunks = chunk_ids
        #    Graph edges will store evidenceChunkIds = chunk_ids
        nodes_created, edges_created, entity_keys = await self.graph_service.ingest(
            request,
            chunk_ids=chunk_ids
        )

        # 3. Update RAG chunks with discovered entity keys (bidirectional link)
        #    Each chunk now knows which graph entities it references
        if entity_keys and chunk_ids:
            for chunk_id in chunk_ids:
                await self.rag_service.update_chunk_graph_refs(chunk_id, entity_keys)

        logger.info("Ingest complete source=%s chunks=%d nodes=%d edges=%d entities=%d",
                     request.sourceUrn, chunks_count, nodes_created, edges_created, len(entity_keys))

        return IngestResult(
            status="success",
            chunks_count=chunks_count,
            nodes_created=nodes_created,
            edges_created=edges_created,
            chunk_ids=chunk_ids,
            entity_keys=entity_keys
        )

    async def ingest_file(self, file_bytes: bytes, filename: str, request: IngestRequest) -> IngestResult:
        is_image = filename.lower().endswith(('.png', '.jpg', '.jpeg', '.webp', '.bmp', '.gif'))

        if is_image:
            logger.info("Processing image file=%s size=%d – calling Tika OCR", filename, len(file_bytes))
            ocr_text = await self.tika_client.process_file(file_bytes, filename)

            ocr_threshold = settings.OCR_TEXT_THRESHOLD
            if ocr_text and len(ocr_text.strip()) > ocr_threshold:
                text = ocr_text
                request.kind = "document"
                logger.info("OCR sufficient for %s (%d chars), skipping VLM", filename, len(ocr_text.strip()))
            else:
                logger.info("OCR insufficient for %s (%d chars), calling VLM model=%s",
                            filename, len((ocr_text or "").strip()), settings.VISION_MODEL)
                try:
                    text = await self.image_service.describe_image(file_bytes)
                    request.kind = "image"
                    logger.info("VLM description ready for %s (%d chars)", filename, len(text))
                except Exception as e:
                    logger.warning("VLM failed for %s: %s – falling back to OCR", filename, e)
                    text = ocr_text
                    request.kind = "image_ocr"
        else:
            logger.info("Processing document file=%s size=%d – calling Tika", filename, len(file_bytes))
            text = await self.tika_client.process_file(file_bytes, filename)
            logger.info("Tika extracted %d chars from %s", len(text or ""), filename)

        request.content = text
        return await self.ingest(request)

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

    async def retrieve(self, request: RetrievalRequest) -> EvidencePack:
        """
        Retrieve evidence using hybrid RAG + Graph approach.

        Uses the HybridRetriever for advanced retrieval with:
        - Vector similarity search (RAG)
        - Graph traversal expansion
        - Entity extraction from query
        - Reciprocal Rank Fusion (RRF) scoring
        - Source diversity
        """
        return await self.hybrid_retriever.retrieve(
            request,
            expand_graph=request.expandGraph,
            extract_entities=True,
            use_rrf=True,
            max_graph_hops=2,
            max_seeds=10,
            diversity_factor=0.7
        )

    async def retrieve_simple(self, request: RetrievalRequest) -> EvidencePack:
        """
        Simple RAG-only retrieval without graph expansion.

        Use this for fast queries where graph context is not needed.
        """
        return await self.rag_service.retrieve(request)

    async def traverse(self, request: TraversalRequest) -> list[GraphNode]:
        return await self.graph_service.traverse(request)

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
                text = await self.tika_client.process_file(doc.page_content.encode('utf-8'), "page.html")
            except Exception as e:
                logger.warning("Tika extraction failed for %s: %s", source_url, e)
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

    async def _check_link_relevance(self, text: str, root_url: str, page_url: str) -> bool:
        """Let LLM decide if a sub-page is relevant for indexing.
        Uses CPU ingest instance with simple (7B) model for fast classification."""
        from langchain_ollama import ChatOllama

        llm = ChatOllama(
            base_url=settings.OLLAMA_INGEST_BASE_URL,
            model=settings.INGEST_MODEL_SIMPLE,
            format="json",
            temperature=0
        )

        truncated = text[:3000] if len(text) > 3000 else text
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
            response = await llm.ainvoke(prompt)
            import json
            result = json.loads(response.content)
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

        # Process attachments
        for file_bytes, filename in attachments:
            try:
                is_image = filename.lower().endswith(('.png', '.jpg', '.jpeg', '.webp', '.bmp', '.gif'))

                if is_image:
                    logger.info("Attachment %s is image – calling Tika OCR", filename)
                    ocr_text = await self.tika_client.process_file(file_bytes, filename)

                    ocr_threshold = settings.OCR_TEXT_THRESHOLD
                    if ocr_text and len(ocr_text.strip()) > ocr_threshold:
                        text = ocr_text
                        content_type = "image_ocr"
                    else:
                        logger.info("OCR insufficient for %s, calling VLM model=%s", filename, settings.VISION_MODEL)
                        try:
                            text = await self.image_service.describe_image(file_bytes)
                            content_type = "image"
                        except Exception as e:
                            logger.warning("VLM failed for attachment %s: %s", filename, e)
                            text = ocr_text
                            content_type = "image_ocr"
                else:
                    logger.info("Attachment %s – calling Tika", filename)
                    text = await self.tika_client.process_file(file_bytes, filename)
                    content_type = "document"

                all_content_parts.append(f"=== ATTACHMENT: {filename} ===\n{text}")
                attachment_results.append(AttachmentResult(
                    filename=filename,
                    status="success",
                    contentType=content_type,
                    extractedText=text[:500] if text else None  # Truncate for result
                ))
            except Exception as e:
                logger.warning("Attachment processing failed file=%s: %s", filename, e)
                attachment_results.append(AttachmentResult(
                    filename=filename,
                    status="failed",
                    contentType="unknown",
                    error=str(e)
                ))

        # Combine all content
        combined_content = "\n\n".join(all_content_parts)

        # Create ingest request for combined content
        ingest_req = IngestRequest(
            clientId=request.clientId,
            projectId=request.projectId,
            sourceUrn=request.sourceUrn,
            kind=request.sourceType,
            content=combined_content,
            metadata={
                **request.metadata,
                "subject": request.subject,
                "sourceType": request.sourceType,
                "attachmentCount": len(attachments),
            },
            observedAt=request.observedAt
        )

        # Ingest to RAG + Graph
        ingest_result = await self.ingest(ingest_req)

        # Generate summary for routing
        summary_result = await self._generate_summary(
            content=combined_content,
            source_type=request.sourceType,
            subject=request.subject
        )

        attachments_processed = sum(1 for r in attachment_results if r.status == "success")
        attachments_failed = sum(1 for r in attachment_results if r.status == "failed")

        logger.info("Full ingest complete source=%s chunks=%d nodes=%d edges=%d "
                     "attachments_ok=%d attachments_fail=%d actionable=%s",
                     request.sourceUrn, ingest_result.chunks_count,
                     ingest_result.nodes_created, ingest_result.edges_created,
                     attachments_processed, attachments_failed,
                     summary_result["hasActionableContent"])

        return FullIngestResult(
            status="success",
            chunks_count=ingest_result.chunks_count,
            nodes_created=ingest_result.nodes_created,
            edges_created=ingest_result.edges_created,
            attachments_processed=attachments_processed,
            attachments_failed=attachments_failed,
            summary=summary_result["summary"],
            entities=summary_result["entities"],
            hasActionableContent=summary_result["hasActionableContent"],
            suggestedActions=summary_result["suggestedActions"]
        )

    async def _generate_summary(
        self,
        content: str,
        source_type: str,
        subject: str = None
    ) -> dict:
        """
        Generate summary and detect actionable content using LLM.
        """
        from langchain_ollama import ChatOllama
        from app.core.config import settings
        import json

        # Use CPU ingest instance with complex (14B) model for accurate entity extraction
        llm = ChatOllama(
            base_url=settings.OLLAMA_INGEST_BASE_URL,
            model=settings.INGEST_MODEL_COMPLEX,
            format="json"
        )

        # Truncate content for summary (first 8000 chars)
        truncated = content[:8000] if len(content) > 8000 else content

        prompt = f"""Analyze this {source_type or 'content'} and provide a JSON response:

Subject: {subject or 'N/A'}

Content:
{truncated}

Respond with JSON:
{{
    "summary": "2-3 sentence summary of the main points",
    "entities": ["list", "of", "key", "entities", "mentioned"],
    "hasActionableContent": true/false,
    "suggestedActions": ["action1", "action2"]
}}

hasActionableContent is TRUE if:
- This is an email/message requiring a reply
- This is a task/issue assigned to someone
- This is a code review request
- This contains a question needing an answer
- This is a meeting action item
- This requires some follow-up action

suggestedActions examples: "reply_email", "review_code", "fix_issue", "answer_question", "schedule_meeting"

Respond ONLY with valid JSON."""

        try:
            logger.info("Calling LLM for summary generation model=%s source_type=%s",
                        settings.INGEST_MODEL_COMPLEX, source_type)
            response = await llm.ainvoke(prompt)
            result = json.loads(response.content)
            logger.info("Summary generated entities=%d actionable=%s",
                        len(result.get("entities", [])), result.get("hasActionableContent", False))
            return {
                "summary": result.get("summary", "No summary available"),
                "entities": result.get("entities", []),
                "hasActionableContent": result.get("hasActionableContent", False),
                "suggestedActions": result.get("suggestedActions", [])
            }
        except Exception as e:
            logger.warning("Summary generation failed: %s", e)
            return {
                "summary": f"Content from {source_type}: {subject or 'No subject'}",
                "entities": [],
                "hasActionableContent": False,
                "suggestedActions": []
            }

