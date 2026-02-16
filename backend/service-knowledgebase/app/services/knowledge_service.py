import asyncio
import logging
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
)
from app.services.rag_service import RagService
from app.services.graph_service import GraphService
from app.services.hybrid_retriever import HybridRetriever
from app.services.clients.tika_client import TikaClient
from app.services.clients.joern_client import JoernClient, JoernResultDto
from app.services.image_service import ImageService
from app.services.llm_extraction_queue import LLMExtractionQueue, ExtractionTask
from app.core.config import settings
from app.db.arango import get_arango_db
from langchain_community.document_loaders import RecursiveUrlLoader
from langchain_ollama import ChatOllama

logger = logging.getLogger(__name__)


class KnowledgeService:
    def __init__(self, extraction_queue: LLMExtractionQueue = None):
        self.rag_service = RagService()
        self.graph_service = GraphService()
        self.hybrid_retriever = HybridRetriever(self.rag_service, self.graph_service)
        self.tika_client = TikaClient()
        self.joern_client = JoernClient()
        self.image_service = ImageService()
        self.extraction_queue = extraction_queue  # Async LLM extraction queue
        self._arango_db = get_arango_db()
        self._ensure_crawl_schema()
        # LLM for ingest tasks (simple relevance check, complex summary)
        self.ingest_llm_simple = ChatOllama(
            base_url=settings.OLLAMA_INGEST_BASE_URL,
            model=settings.INGEST_MODEL_SIMPLE,
            format="json",
            temperature=0
        )
        self.ingest_llm_complex = ChatOllama(
            base_url=settings.OLLAMA_INGEST_BASE_URL,
            model=settings.INGEST_MODEL_COMPLEX,
            format="json",
        )

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

    async def ingest(self, request: IngestRequest, embedding_priority: int | None = None) -> IngestResult:
        """
        Ingest content with async LLM extraction.

        Flow:
        1. RAG Ingest → get chunk IDs (fast, returns immediately)
        2. Enqueue LLM extraction task → background worker processes later
        3. Return immediately without waiting for LLM

        LLM extraction happens asynchronously:
        - Background worker polls queue from PVC
        - Calls graph_service.ingest() with LLM extraction
        - Updates RAG chunks with entity keys after completion
        """
        logger.info(
            "KB_WRITE: INGEST_START sourceUrn=%s kind=%s clientId=%s projectId=%s groupId=%s content_len=%d priority=%s",
            request.sourceUrn, request.kind or "?", request.clientId,
            request.projectId or "", request.groupId or "", len(request.content or ""), embedding_priority
        )

        # 1. RAG Ingest - get chunk IDs (fast)
        chunks_count, chunk_ids = await self.rag_service.ingest(request, embedding_priority=embedding_priority)
        logger.info(
            "KB_WRITE: RAG_INGEST_DONE sourceUrn=%s chunks=%d chunk_ids_sample=%s",
            request.sourceUrn, chunks_count, chunk_ids[:3] if len(chunk_ids) > 3 else chunk_ids
        )

        # 2. Enqueue LLM extraction task (always async, priority queue handles ordering)
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
        )
        await self.extraction_queue.enqueue(task)
        logger.info(
            "KB_WRITE: LLM_EXTRACTION_QUEUED sourceUrn=%s task_id=%s priority=%d",
            request.sourceUrn, task.task_id, priority
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

        result = await self.hybrid_retriever.retrieve(
            request,
            expand_graph=request.expandGraph,
            extract_entities=True,
            use_rrf=True,
            max_graph_hops=2,
            max_seeds=10,
            diversity_factor=0.7,
            embedding_priority=embedding_priority
        )

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
        llm = self.ingest_llm_simple

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

        # Ingest to RAG + Graph (async - returns immediately, LLM extraction queued)
        ingest_result = await self.ingest(ingest_req)

        # Summary generation is NOT blocking - return immediately with placeholder values
        # Real routing hints will be available after background LLM extraction completes
        # TODO: Add summary generation to extraction queue or use simple heuristics

        attachments_processed = sum(1 for r in attachment_results if r.status == "success")
        attachments_failed = sum(1 for r in attachment_results if r.status == "failed")

        logger.info("Full ingest complete (RAG only) source=%s chunks=%d "
                     "attachments_ok=%d attachments_fail=%d (LLM extraction + summary queued)",
                     request.sourceUrn, ingest_result.chunks_count,
                     attachments_processed, attachments_failed)

        return FullIngestResult(
            status="success",
            chunks_count=ingest_result.chunks_count,
            nodes_created=0,  # Will be updated by background worker
            edges_created=0,  # Will be updated by background worker
            attachments_processed=attachments_processed,
            attachments_failed=attachments_failed,
            summary=request.subject or "Processing...",
            entities=[],
            hasActionableContent=False,  # Conservative default - better to process later than block
            suggestedActions=[],
            hasFutureDeadline=False,
            suggestedDeadline=None,
            isAssignedToMe=False,
            urgency="normal",
        )

    async def _generate_summary(
        self,
        content: str,
        source_type: str,
        subject: str = None
    ) -> dict:
        """
        Generate summary, detect actionable content, deadlines,
        and assignment using LLM.
        """
        import json

        # Use CPU ingest instance with complex (14B) model for accurate entity extraction
        llm = self.ingest_llm_complex

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
    "suggestedActions": ["action1", "action2"],
    "hasFutureDeadline": true/false,
    "suggestedDeadline": "ISO-8601 datetime or null",
    "assignedTo": "person or team name, or null",
    "urgency": "urgent/normal/low"
}}

hasActionableContent is TRUE if:
- This is an email/message requiring a reply
- This is a task/issue assigned to someone
- This is a code review request
- This contains a question needing an answer
- This is a meeting action item
- This requires some follow-up action

hasFutureDeadline is TRUE if:
- Content mentions a specific future deadline or due date
- A sprint end date, milestone, or release date is referenced
- "by Friday", "due March 15", "deadline: 2025-04-01", etc.

suggestedDeadline: If hasFutureDeadline is true, provide the deadline as ISO-8601 datetime string (e.g., "2025-04-01T00:00:00"). If unclear, set null.

assignedTo: Extract the person or team this task/issue is assigned to. Look for patterns like "assigned to X", "assignee: X", "@mentions", "please X do this". Set null if no assignment found.

urgency:
- "urgent" if marked as urgent/critical/blocker/P0, or has very near deadline (<24h)
- "normal" for regular tasks, standard priority
- "low" for nice-to-have, low priority, informational

suggestedActions examples: "reply_email", "review_code", "fix_issue", "answer_question", "schedule_meeting"

Respond ONLY with valid JSON."""

        try:
            logger.info("Calling LLM for summary generation model=%s source_type=%s",
                        settings.INGEST_MODEL_COMPLEX, source_type)
            response = await llm.ainvoke(prompt)
            result = json.loads(response.content)
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

