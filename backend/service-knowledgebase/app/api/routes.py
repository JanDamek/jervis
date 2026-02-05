from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from app.api.models import (
    IngestRequest, IngestResult, RetrievalRequest, EvidencePack,
    TraversalRequest, GraphNode, CrawlRequest,
    FullIngestRequest, FullIngestResult,
    HybridRetrievalRequest, HybridEvidenceItem, HybridEvidencePack
)
from app.services.knowledge_service import KnowledgeService
from app.services.clients.joern_client import JoernResultDto
from typing import List, Optional
import json

router = APIRouter()
service = KnowledgeService()

@router.post("/ingest", response_model=IngestResult)
async def ingest(request: IngestRequest):
    try:
        return await service.ingest(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/ingest/file", response_model=IngestResult)
async def ingest_file(
    file: UploadFile = File(...),
    clientId: str = Form(...),
    projectId: str = Form(None),
    sourceUrn: str = Form(None),
    kind: str = Form("file"),
    metadata: str = Form("{}")
):
    try:
        meta = json.loads(metadata)
        
        request = IngestRequest(
            clientId=clientId,
            projectId=projectId,
            sourceUrn=sourceUrn or file.filename,
            kind=kind,
            content="", # Will be filled by Tika
            metadata=meta
        )
        
        content = await file.read()
        return await service.ingest_file(content, file.filename, request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/crawl", response_model=IngestResult)
async def crawl(request: CrawlRequest):
    try:
        return await service.crawl(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/retrieve", response_model=EvidencePack)
async def retrieve(request: RetrievalRequest):
    """
    Standard hybrid retrieval.

    Combines RAG vector search with graph expansion.
    Uses default settings for hybrid retrieval.
    """
    try:
        return await service.retrieve(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/retrieve/simple", response_model=EvidencePack)
async def retrieve_simple(request: RetrievalRequest):
    """
    Simple RAG-only retrieval without graph expansion.

    Faster but less comprehensive. Use for quick lookups.
    """
    try:
        return await service.retrieve_simple(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/retrieve/hybrid", response_model=EvidencePack)
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


@router.post("/traverse", response_model=List[GraphNode])
async def traverse(request: TraversalRequest):
    try:
        return await service.traverse(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/analyze/code", response_model=JoernResultDto)
async def analyze_code(query: str, projectZipBase64: str = None):
    try:
        return await service.analyze_code(query, projectZipBase64)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/graph/node/{node_key}", response_model=Optional[GraphNode])
async def get_graph_node(
    node_key: str,
    clientId: str = "",
    projectId: str = None
):
    """Get a single graph node by key with multi-tenant filtering."""
    try:
        return await service.graph_service.get_node(node_key, clientId, projectId)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/graph/search", response_model=List[GraphNode])
async def search_graph_nodes(
    query: str,
    clientId: str = "",
    projectId: str = None,
    nodeType: str = None,
    limit: int = 20
):
    """Search graph nodes by label with multi-tenant filtering."""
    try:
        return await service.graph_service.search_nodes(
            query=query,
            client_id=clientId,
            project_id=projectId,
            node_type=nodeType,
            limit=limit
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/graph/node/{node_key}/evidence")
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


# === Query Analysis Endpoints ===

@router.get("/query/entities")
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


# === Alias Management Endpoints ===

@router.get("/alias/resolve")
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


@router.post("/alias/register")
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


@router.post("/alias/merge")
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


@router.get("/alias/list/{canonical_key}")
async def list_aliases(canonical_key: str, clientId: str = ""):
    """Get all aliases that point to a canonical key."""
    try:
        aliases = await service.graph_service.alias_registry.get_aliases(clientId, canonical_key)
        return {"canonical": canonical_key, "aliases": aliases}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/alias/stats")
async def alias_stats(clientId: str = ""):
    """Get statistics about the alias registry for a client."""
    try:
        stats = await service.graph_service.alias_registry.get_stats(clientId)
        return stats
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/ingest/full", response_model=FullIngestResult)
async def ingest_full(
    clientId: str = Form(...),
    sourceUrn: str = Form(...),
    sourceType: str = Form(""),
    subject: str = Form(None),
    content: str = Form(""),
    projectId: str = Form(None),
    metadata: str = Form("{}"),
    attachments: List[UploadFile] = File(default=[])
):
    """
    Full document ingestion with attachments.

    Accepts multipart form with:
    - Document metadata (clientId, sourceUrn, sourceType, subject, content)
    - Multiple file attachments (images processed with vision, docs with Tika)

    Returns summary and routing hints for qualification.
    """
    try:
        meta = json.loads(metadata)

        request = FullIngestRequest(
            clientId=clientId,
            projectId=projectId,
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

        return await service.ingest_full(request, attachment_list)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
