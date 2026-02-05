from app.api.models import (
    IngestRequest, IngestResult, RetrievalRequest, EvidencePack,
    TraversalRequest, GraphNode, CrawlRequest,
    FullIngestRequest, FullIngestResult, AttachmentResult
)
from app.services.rag_service import RagService
from app.services.graph_service import GraphService
from app.services.clients.tika_client import TikaClient
from app.services.clients.joern_client import JoernClient, JoernQueryDto, JoernResultDto
from app.services.image_service import ImageService
from langchain_community.document_loaders import RecursiveUrlLoader
import asyncio

class KnowledgeService:
    def __init__(self):
        self.rag_service = RagService()
        self.graph_service = GraphService()
        self.tika_client = TikaClient()
        self.joern_client = JoernClient()
        self.image_service = ImageService()

    async def ingest(self, request: IngestRequest) -> IngestResult:
        # 1. RAG Ingest
        chunks_count = await self.rag_service.ingest(request)
        
        # 2. Graph Ingest
        nodes_created, edges_created = await self.graph_service.ingest(request)
        
        return IngestResult(
            status="success",
            chunks_count=chunks_count,
            nodes_created=nodes_created,
            edges_created=edges_created
        )

    async def ingest_file(self, file_bytes: bytes, filename: str, request: IngestRequest) -> IngestResult:
        # Check if image
        is_image = filename.lower().endswith(('.png', '.jpg', '.jpeg', '.webp', '.bmp', '.gif'))
        
        if is_image:
            try:
                text = await self.image_service.describe_image(file_bytes)
                request.kind = "image"
            except Exception as e:
                print(f"Image description failed: {e}")
                # Fallback to Tika (OCR) if vision model fails?
                text = await self.tika_client.process_file(file_bytes, filename)
        else:
            # Use Tika to extract text
            text = await self.tika_client.process_file(file_bytes, filename)
        
        # Update request content
        request.content = text
        
        # Proceed with normal ingest
        return await self.ingest(request)

    async def analyze_code(self, query: str, project_zip_base64: str = None) -> JoernResultDto:
        return await self.joern_client.run(query, project_zip_base64)

    async def retrieve(self, request: RetrievalRequest) -> EvidencePack:
        # 1. RAG Retrieve
        evidence = await self.rag_service.retrieve(request)
        
        # 2. (Optional) Graph Expansion could happen here
        
        return evidence

    async def traverse(self, request: TraversalRequest) -> list[GraphNode]:
        return await self.graph_service.traverse(request)

    async def crawl(self, request: CrawlRequest) -> IngestResult:
        # Use Tika for extraction
        # We'll use RecursiveUrlLoader to fetch raw HTML, then send to Tika
        
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
        
        total_chunks = 0
        total_nodes = 0
        total_edges = 0
        
        for doc in docs:
            # Extract text using Tika
            try:
                # doc.page_content is raw HTML
                text = await self.tika_client.process_file(doc.page_content.encode('utf-8'), "page.html")
            except Exception as e:
                print(f"Failed to extract text with Tika for {doc.metadata.get('source')}: {e}")
                continue

            ingest_req = IngestRequest(
                clientId=request.clientId or "GENERAL",
                projectId=request.projectId,
                sourceUrn=doc.metadata.get("source", request.url),
                kind="documentation",
                content=text,
                metadata=doc.metadata
            )
            
            res = await self.ingest(ingest_req)
            total_chunks += res.chunks_count
            total_nodes += res.nodes_created
            total_edges += res.edges_created
            
        return IngestResult(
            status="success",
            chunks_count=total_chunks,
            nodes_created=total_nodes,
            edges_created=total_edges
        )

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

        # Add main content
        if request.content:
            all_content_parts.append(f"=== MAIN CONTENT ===\n{request.content}")

        # Process attachments
        for file_bytes, filename in attachments:
            try:
                is_image = filename.lower().endswith(('.png', '.jpg', '.jpeg', '.webp', '.bmp', '.gif'))

                if is_image:
                    # Use vision model
                    try:
                        text = await self.image_service.describe_image(file_bytes)
                        content_type = "image"
                    except Exception as e:
                        # Fallback to Tika OCR
                        text = await self.tika_client.process_file(file_bytes, filename)
                        content_type = "image_ocr"
                else:
                    # Use Tika for documents
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

        # Use small/fast model for summary
        llm = ChatOllama(
            base_url=settings.OLLAMA_BASE_URL,
            model="qwen2.5:7b",  # Small model for speed
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
            response = await llm.ainvoke(prompt)
            result = json.loads(response.content)
            return {
                "summary": result.get("summary", "No summary available"),
                "entities": result.get("entities", []),
                "hasActionableContent": result.get("hasActionableContent", False),
                "suggestedActions": result.get("suggestedActions", [])
            }
        except Exception as e:
            print(f"Summary generation failed: {e}")
            return {
                "summary": f"Content from {source_type}: {subject or 'No subject'}",
                "entities": [],
                "hasActionableContent": False,
                "suggestedActions": []
            }

