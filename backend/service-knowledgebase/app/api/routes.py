from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from app.api.models import (
    IngestRequest, IngestResult, RetrievalRequest, EvidencePack,
    TraversalRequest, GraphNode, CrawlRequest,
    FullIngestRequest, FullIngestResult
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
    try:
        return await service.retrieve(request)
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
