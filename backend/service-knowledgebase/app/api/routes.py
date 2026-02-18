"""Knowledge Base API routes.

Routes are split into read_router and write_router so the KB service can run
in read-only or write-only mode via the KB_MODE environment variable.
The legacy `router` includes both for backward compatibility (KB_MODE=all).
"""

from fastapi import APIRouter, HTTPException, UploadFile, File, Form, Request
from starlette.responses import StreamingResponse
from app.api.models import (
    IngestRequest, IngestResult, RetrievalRequest, EvidencePack,
    TraversalRequest, GraphNode, CrawlRequest,
    FullIngestRequest, FullIngestResult,
    HybridRetrievalRequest, HybridEvidenceItem, HybridEvidencePack,
    PurgeRequest, PurgeResult,
    ListByKindRequest,
    GitStructureIngestRequest, GitStructureIngestResult,
    GitCommitIngestRequest, GitCommitIngestResult,
    CpgIngestRequest, CpgIngestResult,
    JoernScanRequest, JoernScanResult,
)
from app.services.knowledge_service import KnowledgeService
from app.services.clients.joern_client import JoernResultDto
from typing import List, Optional
import json

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


@write_router.post("/ingest", response_model=IngestResult)
async def ingest(request: IngestRequest, http_request: Request):
    try:
        # Read priority from header (orchestrator sends 1, background indexing sends 4)
        priority = http_request.headers.get("X-Ollama-Priority")
        priority_int = int(priority) if priority and priority.isdigit() else None
        return await service.ingest(request, embedding_priority=priority_int)
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

        request = FullIngestRequest(
            clientId=clientId,
            projectId=projectId,
            groupId=groupId,
            sourceUrn=sourceUrn,
            sourceType=sourceType,
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
# Legacy combined router (backward compat for KB_MODE=all)
# ---------------------------------------------------------------------------

router = APIRouter()
router.include_router(read_router)
router.include_router(write_router)
