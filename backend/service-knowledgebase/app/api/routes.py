"""Knowledge Base API routes.

Routes are split into read_router and write_router so the KB service can run
in read-only or write-only mode via the KB_MODE environment variable.
The legacy `router` includes both for backward compatibility (KB_MODE=all).
"""

import httpx
import logging
from fastapi import APIRouter, HTTPException, UploadFile, File, Form, Request
from starlette.responses import StreamingResponse
from app.api.models import (
    IngestRequest, IngestResult, RetrievalRequest, EvidencePack,
    TraversalRequest, GraphNode, CrawlRequest,
    FullIngestRequest, FullIngestResult, SourceType,
    HybridRetrievalRequest, HybridEvidenceItem, HybridEvidencePack,
    PurgeRequest, PurgeResult,
    ListByKindRequest,
    GitStructureIngestRequest, GitStructureIngestResult,
    GitCommitIngestRequest, GitCommitIngestResult,
    CpgIngestRequest, CpgIngestResult,
    JoernScanRequest, JoernScanResult,
    KbDocumentUploadRequest, KbDocumentDto, KbDocumentUpdateRequest,
    KbDocumentCategoryEnum,
)
from app.services.knowledge_service import KnowledgeService
from app.services.clients.joern_client import JoernResultDto
from typing import List, Optional
import json

logger = logging.getLogger(__name__)

# Global service - will be initialized in main.py lifespan with extraction queue
service: KnowledgeService = None  # type: ignore

# ---------------------------------------------------------------------------
# READ router — retrieve, search, traverse, graph, alias/resolve, chunks
# ---------------------------------------------------------------------------

read_router = APIRouter()


@read_router.post("/retrieve", response_model=EvidencePack)
async def retrieve(request: RetrievalRequest, http_request: Request):
    """
    Standard hybrid retrieval.

    Combines RAG vector search with graph expansion.
    Uses default settings for hybrid retrieval.
    """
    try:
        # Read priority from header (orchestrator sends X-Ollama-Priority: 1)
        priority = http_request.headers.get("X-Ollama-Priority")
        priority_int = int(priority) if priority and priority.isdigit() else None
        return await service.retrieve(request, embedding_priority=priority_int)
    except httpx.HTTPStatusError as e:
        logger.warning("Retrieve failed (embedding unavailable): %s", e)
        return EvidencePack(items=[])
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.post("/retrieve/simple", response_model=EvidencePack)
async def retrieve_simple(request: RetrievalRequest, http_request: Request):
    """
    Simple RAG-only retrieval without graph expansion.

    Faster but less comprehensive. Use for quick lookups.
    """
    try:
        # Read priority from header
        priority = http_request.headers.get("X-Ollama-Priority")
        priority_int = int(priority) if priority and priority.isdigit() else None
        return await service.retrieve_simple(request, embedding_priority=priority_int)
    except httpx.HTTPStatusError as e:
        logger.warning("Retrieve simple failed (embedding unavailable): %s", e)
        return EvidencePack(items=[])
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.post("/retrieve/hybrid", response_model=EvidencePack)
async def retrieve_hybrid(request: HybridRetrievalRequest):
    """
    Advanced hybrid retrieval with full control over settings.

    Configurable options:
    - expandGraph: Enable/disable graph expansion
    - extractEntities: Enable/disable entity extraction from query
    - useRRF: Use Reciprocal Rank Fusion for score combination
    - maxGraphHops: How far to traverse in graph (1-3 recommended)
    - maxSeeds: Max seed nodes for expansion
    - diversityFactor: Source diversity (lower = more diverse)

    Returns detailed scoring breakdown for each result.
    """
    try:
        result = await service.hybrid_retriever.retrieve(
            request=RetrievalRequest(
                query=request.query,
                clientId=request.clientId,
                projectId=request.projectId,
                groupId=request.groupId,
                maxResults=request.maxResults,
                minConfidence=request.minConfidence,
                expandGraph=request.expandGraph
            ),
            expand_graph=request.expandGraph,
            extract_entities=request.extractEntities,
            use_rrf=request.useRRF,
            max_graph_hops=request.maxGraphHops,
            max_seeds=request.maxSeeds,
            diversity_factor=request.diversityFactor
        )
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.post("/traverse", response_model=List[GraphNode])
async def traverse(request: TraversalRequest):
    try:
        return await service.traverse(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.post("/analyze/code", response_model=JoernResultDto)
async def analyze_code(query: str, workspacePath: str = ""):
    try:
        return await service.analyze_code(query, workspacePath)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.get("/graph/node/{node_key}", response_model=Optional[GraphNode])
async def get_graph_node(
    node_key: str,
    clientId: str = "",
    projectId: str = None,
    groupId: str = None
):
    """Get a single graph node by key with multi-tenant filtering."""
    try:
        return await service.graph_service.get_node(node_key, clientId, projectId, groupId)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.get("/graph/search", response_model=List[GraphNode])
async def search_graph_nodes(
    query: str,
    clientId: str = "",
    projectId: str = None,
    groupId: str = None,
    nodeType: str = None,
    branchName: str = None,
    limit: int = 20
):
    """Search graph nodes by label with multi-tenant filtering.

    Optional branchName filter scopes results to a specific branch
    (applies to file, class, and other branch-scoped node types).
    """
    try:
        return await service.graph_service.search_nodes(
            query=query,
            client_id=clientId,
            project_id=projectId,
            group_id=groupId,
            node_type=nodeType,
            branch_name=branchName,
            limit=limit
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.get("/graph/node/{node_key}/evidence")
async def get_node_evidence(node_key: str, clientId: str = ""):
    """Get RAG chunks that support a graph node."""
    try:
        chunk_ids = await service.graph_service.get_node_chunks(node_key, clientId)
        if not chunk_ids:
            return {"chunks": []}
        chunks = await service.rag_service.get_chunks_by_ids(chunk_ids)
        return {"chunks": chunks}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.get("/query/entities")
async def extract_query_entities(query: str):
    """
    Extract entities from a query string.

    Useful for debugging and understanding what the hybrid retriever detects.
    Returns list of normalized entity references.
    """
    try:
        entities = service.hybrid_retriever._extract_query_entities(query)
        return {
            "query": query,
            "entities": entities,
            "count": len(entities)
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.get("/alias/resolve")
async def resolve_alias(alias: str, clientId: str = ""):
    """
    Resolve an alias to its canonical key.

    Returns the canonical key, or the normalized alias if not found.
    """
    try:
        canonical = await service.graph_service.alias_registry.resolve(clientId, alias)
        return {"alias": alias, "canonical": canonical}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.get("/alias/list/{canonical_key}")
async def list_aliases(canonical_key: str, clientId: str = ""):
    """Get all aliases that point to a canonical key."""
    try:
        aliases = await service.graph_service.alias_registry.get_aliases(clientId, canonical_key)
        return {"canonical": canonical_key, "aliases": aliases}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.get("/alias/stats")
async def alias_stats(clientId: str = ""):
    """Get statistics about the alias registry for a client."""
    try:
        stats = await service.graph_service.alias_registry.get_stats(clientId)
        return stats
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.post("/chunks/by-kind")
async def list_chunks_by_kind(request: ListByKindRequest):
    """List all RAG chunks matching a specific kind with tenant filtering."""
    try:
        results = await service.rag_service.list_by_kind(
            client_id=request.clientId,
            project_id=request.projectId,
            kind=request.kind,
            limit=request.maxResults,
        )
        return {"chunks": results}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.post("/joern/scan", response_model=JoernScanResult)
async def joern_scan(request: JoernScanRequest):
    """
    Run a pre-built Joern code analysis scan.

    Available scan types:
    - security: Find SQL injection, command injection, hardcoded secrets
    - dataflow: Identify HTTP input sources and sensitive sinks
    - callgraph: Method fan-out analysis and dead code detection
    - complexity: Cyclomatic complexity and long method detection

    Runs Joern as a K8s Job on the shared PVC.
    """
    try:
        return await service.run_joern_scan(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ---------------------------------------------------------------------------
# WRITE router — ingest, crawl, purge, alias/register, alias/merge
# ---------------------------------------------------------------------------

write_router = APIRouter()


@write_router.get("/queue")
async def get_extraction_queue(limit: int = 100):
    """Return extraction queue contents for UI display."""
    queue = service.extraction_queue
    if queue is None:
        return {"items": [], "stats": {"total": 0, "pending": 0, "in_progress": 0, "failed": 0}}

    items = await queue.list_queue(limit=limit)
    stats = await queue.stats()
    return {"items": items, "stats": stats}


@write_router.post("/ingest", response_model=IngestResult)
async def ingest(request: IngestRequest, http_request: Request):
    try:
        # Read priority from header (orchestrator sends 1, background indexing sends 4)
        priority = http_request.headers.get("X-Ollama-Priority")
        priority_int = int(priority) if priority and priority.isdigit() else None
        return await service.ingest(request, embedding_priority=priority_int)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/ingest-queue")
async def ingest_queue(request: IngestRequest, http_request: Request):
    """Fire-and-forget ingest — accepts data, queues processing, returns immediately.

    Used by MCP kb_store — caller doesn't need to wait for embedding/extraction.
    Processing (RAG embedding + LLM extraction) happens in background.
    """
    import asyncio

    priority = http_request.headers.get("X-Ollama-Priority")
    priority_int = int(priority) if priority and priority.isdigit() else None

    asyncio.create_task(service.ingest(request, embedding_priority=priority_int))

    from starlette.responses import JSONResponse
    return JSONResponse(status_code=202, content={"accepted": True})


@write_router.post("/ingest-immediate", response_model=IngestResult)
async def ingest_immediate(request: IngestRequest, http_request: Request):
    """Synchronous ingest — RAG + LLM extraction in one call.

    Unlike /ingest which queues LLM extraction for background processing,
    this endpoint runs the full pipeline synchronously and returns complete
    results (nodes, edges, entity keys).

    Use for critical writes where data must be searchable immediately.
    """
    try:
        priority = http_request.headers.get("X-Ollama-Priority")
        priority_int = int(priority) if priority and priority.isdigit() else None
        return await service.ingest_immediate(request, embedding_priority=priority_int)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/ingest/file", response_model=IngestResult)
async def ingest_file(
    file: UploadFile = File(...),
    clientId: str = Form(...),
    projectId: str = Form(None),
    groupId: str = Form(None),
    sourceUrn: str = Form(None),
    kind: str = Form("file"),
    metadata: str = Form("{}")
):
    try:
        meta = json.loads(metadata)

        request = IngestRequest(
            clientId=clientId,
            projectId=projectId,
            groupId=groupId,
            sourceUrn=sourceUrn or file.filename,
            kind=kind,
            content="", # Will be filled by Tika
            metadata=meta
        )

        content = await file.read()
        return await service.ingest_file(content, file.filename, request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/ingest/full")
async def ingest_full(
    http_request: Request,
    clientId: str = Form(...),
    sourceUrn: str = Form(...),
    sourceType: str = Form(""),
    subject: str = Form(None),
    content: str = Form(""),
    projectId: str = Form(None),
    groupId: str = Form(None),
    metadata: str = Form("{}"),
    callbackUrl: str = Form(""),
    taskId: str = Form(""),
    attachments: List[UploadFile] = File(default=[])
):
    """
    Full document ingestion with attachments.

    Accepts multipart form with:
    - Document metadata (clientId, sourceUrn, sourceType, subject, content)
    - Multiple file attachments (images processed with vision, docs with Tika)
    - Optional callbackUrl + taskId for push-based progress notifications

    If Accept: application/x-ndjson → streams progress events as NDJSON lines.
    Otherwise → returns single JSON response (backward compatible).
    """
    try:
        meta = json.loads(metadata)
        source_type_enum = SourceType(sourceType) if sourceType else None

        request = FullIngestRequest(
            clientId=clientId,
            projectId=projectId,
            groupId=groupId,
            sourceUrn=sourceUrn,
            sourceType=source_type_enum,
            subject=subject,
            content=content,
            metadata=meta
        )

        # Read all attachments
        attachment_list = []
        for attachment in attachments:
            file_bytes = await attachment.read()
            attachment_list.append((file_bytes, attachment.filename))

        # Check if client wants streaming NDJSON
        accept = http_request.headers.get("accept", "")
        if "ndjson" in accept:
            async def _stream():
                async for event in service.ingest_full_streaming(
                    request, attachment_list,
                    callback_url=callbackUrl,
                    task_id=taskId,
                    client_id=clientId,
                ):
                    yield json.dumps(event) + "\n"

            return StreamingResponse(_stream(), media_type="application/x-ndjson")
        else:
            return await service.ingest_full(request, attachment_list)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/ingest/full/async")
async def ingest_full_async(
    http_request: Request,
    clientId: str = Form(...),
    sourceUrn: str = Form(...),
    sourceType: str = Form(""),
    subject: str = Form(None),
    content: str = Form(""),
    projectId: str = Form(None),
    groupId: str = Form(None),
    metadata: str = Form("{}"),
    callbackUrl: str = Form(...),
    taskId: str = Form(...),
    priority: str = Form(None),
    attachments: List[UploadFile] = File(default=[])
):
    """
    Async full document ingestion — fire-and-forget.

    Accepts same params as /ingest/full plus required callbackUrl + taskId.
    Returns immediately with HTTP 202 Accepted.
    KB processes in background (RAG + LLM summary + graph extraction).
    When done, POSTs result to callbackUrl (/internal/kb-done).
    Progress events are pushed to /internal/kb-progress along the way.
    """
    import asyncio

    try:
        meta = json.loads(metadata)
        source_type_enum = SourceType(sourceType) if sourceType else None

        request = FullIngestRequest(
            clientId=clientId,
            projectId=projectId,
            groupId=groupId,
            sourceUrn=sourceUrn,
            sourceType=source_type_enum,
            subject=subject,
            content=content,
            metadata=meta
        )

        # Read all attachments into memory before returning (they're UploadFile streams)
        attachment_list = []
        for attachment in attachments:
            file_bytes = await attachment.read()
            attachment_list.append((file_bytes, attachment.filename))

        # Parse priority from form field (1=CRITICAL, 4=NORMAL default)
        priority_int = int(priority) if priority and priority.isdigit() else None

        # Spawn background task — processing continues after HTTP response
        asyncio.create_task(service.process_full_async(
            request, attachment_list,
            callback_url=callbackUrl,
            task_id=taskId,
            client_id=clientId,
            embedding_priority=priority_int,
        ))

        from starlette.responses import JSONResponse
        return JSONResponse(
            status_code=202,
            content={"accepted": True, "taskId": taskId},
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/ingest/git-structure", response_model=GitStructureIngestResult)
async def ingest_git_structure(request: GitStructureIngestRequest):
    """Structural ingest of git repository (no LLM).

    Creates graph nodes for repository, branches, files, and classes.
    Called from Kotlin GitContinuousIndexer during initial branch index.
    """
    try:
        return await service.ingest_git_structure(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/ingest/git-commits", response_model=GitCommitIngestResult)
async def ingest_git_commits(request: GitCommitIngestRequest):
    """Ingest structured git commit data into KB graph.

    Creates commit nodes in ArangoDB with edges to branch and file nodes.
    Optional diff_content is ingested as RAG chunks for fulltext search.
    Called from Kotlin GitContinuousIndexer for individual commits.
    """
    try:
        return await service.ingest_git_commits(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/ingest/cpg", response_model=CpgIngestResult)
async def ingest_cpg(request: CpgIngestRequest):
    """Import Joern CPG deep analysis into knowledge graph.

    Runs Joern CPG export (K8s Job) and imports semantic edges:
    - calls: method → method (call graph)
    - extends: class → class (inheritance)
    - uses_type: class → class (type references)

    Called from Kotlin GitContinuousIndexer after structural index completes.
    Requires that tree-sitter structural ingest has already created method/class nodes.
    """
    try:
        return await service.ingest_cpg(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/crawl", response_model=IngestResult)
async def crawl(request: CrawlRequest):
    try:
        return await service.crawl(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/purge", response_model=PurgeResult)
async def purge(request: PurgeRequest):
    """Delete all RAG chunks and clean graph refs for a sourceUrn."""
    try:
        result = await service.purge(request.sourceUrn)
        return PurgeResult(status="success", **result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/retag-group")
async def retag_group(request: dict):
    """Update groupId on all KB items for a project.

    Called when a project's group membership changes.
    Updates both ArangoDB nodes and Weaviate chunks for the given projectId.
    """
    project_id = request.get("projectId", "")
    group_id = request.get("groupId")
    if not project_id:
        raise HTTPException(status_code=400, detail="projectId is required")
    try:
        graph_updated = await service.graph_service.retag_group(project_id, group_id)
        weaviate_updated = await service.rag_service.retag_group(project_id, group_id)
        return {"status": "success", "graphUpdated": graph_updated, "weaviateUpdated": weaviate_updated}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/alias/register")
async def register_alias(
    alias: str,
    canonical: str = None,
    clientId: str = ""
):
    """
    Register an alias in the registry.

    If canonical is not provided, the alias becomes its own canonical.
    """
    try:
        result = await service.graph_service.alias_registry.register(clientId, alias, canonical)
        return {"alias": alias, "canonical": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/alias/merge")
async def merge_aliases(
    sourceKey: str,
    targetKey: str,
    clientId: str = ""
):
    """
    Merge two entities: all aliases pointing to source → point to target.

    Use this when you discover that two entities are actually the same.
    """
    try:
        count = await service.graph_service.alias_registry.merge(clientId, sourceKey, targetKey)
        return {"merged": count, "source": sourceKey, "target": targetKey}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ---------------------------------------------------------------------------
# KB Document Upload & Management
# ---------------------------------------------------------------------------


@write_router.post("/documents/upload", response_model=KbDocumentDto)
async def upload_kb_document(
    file: UploadFile = File(...),
    clientId: str = Form(...),
    projectId: str = Form(None),
    filename: str = Form(None),
    mimeType: str = Form(None),
    storagePath: str = Form(""),
    title: str = Form(None),
    description: str = Form(None),
    category: str = Form("OTHER"),
    tags: str = Form(""),
    contentHash: str = Form(None),
):
    """Upload a document to KB.

    The file binary is sent via multipart. If storagePath is provided,
    it means the Kotlin server already stored the file on shared FS
    and we only need to create the graph node + extract/ingest content.
    If storagePath is empty, the file is ingested directly from the upload.
    """
    try:
        actual_filename = filename or file.filename or "unknown"
        actual_mime = mimeType or file.content_type or "application/octet-stream"
        file_bytes = await file.read()
        actual_size = len(file_bytes)

        tag_list = [t.strip() for t in tags.split(",") if t.strip()] if tags else []

        try:
            cat_enum = KbDocumentCategoryEnum(category)
        except ValueError:
            cat_enum = KbDocumentCategoryEnum.OTHER

        request = KbDocumentUploadRequest(
            clientId=clientId,
            projectId=projectId,
            filename=actual_filename,
            mimeType=actual_mime,
            sizeBytes=actual_size,
            storagePath=storagePath,
            title=title,
            description=description,
            category=cat_enum,
            tags=tag_list,
            contentHash=contentHash,
        )

        return await service.upload_kb_document(request, file_bytes=file_bytes)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/documents/extract-text")
async def extract_text_only(
    file: UploadFile = File(...),
    filename: str = Form(None),
    mimeType: str = Form(None),
):
    """Extract text from a file without RAG indexing.

    Uses VLM-first for images, Tika for documents. Returns extracted text
    and the method used. No graph nodes or RAG chunks are created.

    Used by Kotlin AttachmentExtractionService for Qualifier relevance assessment.
    """
    try:
        actual_filename = filename or file.filename or "unknown"
        actual_mime = mimeType or file.content_type or "application/octet-stream"
        file_bytes = await file.read()

        result = await service.extract_text_only(file_bytes, actual_filename, actual_mime)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/documents/register", response_model=KbDocumentDto)
async def register_kb_document(request: KbDocumentUploadRequest):
    """Register a document already stored on shared FS.

    No file binary is sent — the Kotlin server already stored the file.
    Reads the file from storagePath on the shared PVC for extraction.
    """
    import os

    try:
        file_bytes = None
        data_root = os.environ.get("DATA_ROOT_DIR", "/opt/jervis/data")
        full_path = os.path.join(data_root, request.storagePath)
        if os.path.exists(full_path):
            with open(full_path, "rb") as f:
                file_bytes = f.read()

        return await service.upload_kb_document(request, file_bytes=file_bytes)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.get("/documents", response_model=List[KbDocumentDto])
async def list_kb_documents(clientId: str, projectId: str = None):
    """List all KB documents for a client."""
    try:
        return await service.list_kb_documents(clientId, projectId)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@read_router.get("/documents/{doc_id}", response_model=KbDocumentDto)
async def get_kb_document(doc_id: str):
    """Get a single KB document by ID."""
    try:
        doc = await service.get_kb_document(doc_id)
        if not doc:
            raise HTTPException(status_code=404, detail="Document not found")
        return doc
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.put("/documents/{doc_id}", response_model=KbDocumentDto)
async def update_kb_document(doc_id: str, request: KbDocumentUpdateRequest):
    """Update document metadata (title, description, category, tags)."""
    try:
        doc = await service.update_kb_document(
            doc_id=doc_id,
            title=request.title,
            description=request.description,
            category=request.category.value if request.category else None,
            tags=request.tags,
        )
        if not doc:
            raise HTTPException(status_code=404, detail="Document not found")
        return doc
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.delete("/documents/{doc_id}")
async def delete_kb_document(doc_id: str):
    """Delete a KB document (purges RAG data + graph node).

    Note: The Kotlin server is responsible for deleting the file from shared FS.
    """
    try:
        deleted = await service.delete_kb_document(doc_id)
        if not deleted:
            raise HTTPException(status_code=404, detail="Document not found")
        return {"ok": True, "deleted": doc_id}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@write_router.post("/documents/{doc_id}/reindex")
async def reindex_kb_document(doc_id: str):
    """Re-extract and re-index a document from its file on shared FS."""
    import os

    try:
        doc = await service.get_kb_document(doc_id)
        if not doc:
            raise HTTPException(status_code=404, detail="Document not found")

        data_root = os.environ.get("DATA_ROOT_DIR", "/opt/jervis/data")
        full_path = os.path.join(data_root, doc.storagePath)
        if not os.path.exists(full_path):
            raise HTTPException(status_code=404, detail="Document file not found on disk")

        with open(full_path, "rb") as f:
            file_bytes = f.read()

        success = await service.reindex_kb_document(doc_id, file_bytes)
        if not success:
            raise HTTPException(status_code=500, detail="Reindex failed")
        return {"ok": True, "reindexed": doc_id}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ---------------------------------------------------------------------------
# Legacy combined router (backward compat for KB_MODE=all)
# ---------------------------------------------------------------------------

router = APIRouter()
router.include_router(read_router)
router.include_router(write_router)
