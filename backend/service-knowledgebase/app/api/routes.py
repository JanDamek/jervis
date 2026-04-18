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
    ThoughtTraversalRequest, ThoughtReinforcementRequest,
    ThoughtCreateRequest, ThoughtBootstrapRequest,
    ThoughtMaintenanceRequest, ThoughtTraversalResult,
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


# /retrieve and /retrieve/simple migrated to gRPC
# (KnowledgeRetrieveService.Retrieve / RetrieveSimple on :5501).
# X-Ollama-Priority header hint is dropped — the server-side hybrid
# retriever already picks an embedding priority based on KB_MODE,
# and the header was only read on this shim.


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


# /traverse migrated to gRPC (KnowledgeGraphService.Traverse on :5501).


# /analyze/code migrated to gRPC (KnowledgeRetrieveService.AnalyzeCode).


# /graph/node/{key}, /graph/search, /graph/node/{key}/evidence migrated
# to gRPC (KnowledgeGraphService.{GetNode,SearchNodes,GetNodeEvidence}).
# branch_name filter on SearchNodes is preserved — prefetch.py uses it
# in _graph_search_branch_aware to scope file/class nodes to a branch.


# /query/entities migrated to gRPC (KnowledgeGraphService.ListQueryEntities).


# /alias/resolve, /alias/list/{key}, /alias/stats migrated to gRPC
# (KnowledgeGraphService.ResolveAlias / ListAliases / GetAliasStats
# on :5501 — see app/grpc_server.py).


# /chunks/by-kind + /joern/scan migrated to gRPC
# (KnowledgeRetrieveService.{ListChunksByKind,JoernScan}).


# ---------------------------------------------------------------------------
# WRITE router — ingest, crawl, purge, alias/register, alias/merge
# ---------------------------------------------------------------------------

write_router = APIRouter()


# /queue migrated to gRPC (KnowledgeQueueService.ListQueue on :5501 —
# see app/grpc_server.py).


# /ingest, /ingest-queue, /ingest-immediate migrated to gRPC
# (KnowledgeIngestService.{Ingest,IngestQueue,IngestImmediate} on :5501 —
# see app/grpc_server.py). Priority selection is still the caller's
# responsibility but now travels as a typed field inside RequestContext
# rather than an X-Ollama-Priority HTTP header.


# /ingest/file, /ingest/full, /ingest/full/async migrated to gRPC
# (KnowledgeIngestService.{IngestFile,IngestFull,IngestFullAsync}).
# Inline bytes carry payloads up to the gRPC max_msg_bytes limit
# (64 MiB). Larger payloads should go via the blob side channel
# described in inter-service-contracts-bigbang.md §2.3 — deferred until
# a caller actually needs >64 MiB ingest (current email + meeting
# attachment payloads stay well under that ceiling).


# /ingest/git-structure, /ingest/git-commits, /ingest/cpg migrated to
# gRPC (KnowledgeIngestService on :5501 — see app/grpc_server.py).


# /crawl migrated to gRPC (KnowledgeIngestService.Crawl).


# /purge migrated to gRPC (KnowledgeIngestService.Purge on :5501 — see
# app/grpc_server.py).


# /maintenance/batch, /retag-project, /retag-group migrated to gRPC
# (KnowledgeMaintenanceService on :5501 — see app/grpc_server.py).


# /alias/register, /alias/merge migrated to gRPC
# (KnowledgeGraphService.RegisterAlias / MergeAlias on :5501).


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

    Uses DocumentExtractor (VLM for images, pymupdf for PDFs, python-docx for DOCX). Returns extracted text
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


# /documents/register, /documents, /documents/{doc_id} (GET/PUT/DELETE),
# /documents/{doc_id}/reindex migrated to gRPC
# (KnowledgeDocumentService.{Register,List,Get,Update,Delete,Reindex} on :5501).
# /documents/upload (multipart) and /documents/extract-text (multipart)
# remain on FastAPI until slice 13 — the binary-upload path will use
# the blob side channel described in inter-service-contracts-bigbang.md §2.3.


# ---------------------------------------------------------------------------
# Thought Map endpoints
# ---------------------------------------------------------------------------

# /thoughts/{traverse,stats,reinforce,create,bootstrap,maintain} migrated
# to gRPC (KnowledgeGraphService.Thought* on :5501 — see app/grpc_server.py).


# ---------------------------------------------------------------------------
# Legacy combined router (backward compat for KB_MODE=all)
# ---------------------------------------------------------------------------

router = APIRouter()
router.include_router(read_router)
router.include_router(write_router)
