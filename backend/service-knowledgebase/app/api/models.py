from pydantic import BaseModel, Field
from typing import Optional, Dict, Any, List
from datetime import datetime

class IngestRequest(BaseModel):
    clientId: str
    projectId: Optional[str] = None
    sourceUrn: str
    kind: str = ""
    content: str
    metadata: Dict[str, Any] = {}
    observedAt: datetime = Field(default_factory=datetime.now)

class IngestResult(BaseModel):
    status: str
    chunks_count: int
    nodes_created: int
    edges_created: int

class RetrievalRequest(BaseModel):
    query: str
    clientId: str
    projectId: Optional[str] = None
    asOf: Optional[datetime] = None
    minConfidence: float = 0.0
    maxResults: int = 10

class EvidenceItem(BaseModel):
    content: str
    score: float
    sourceUrn: str
    metadata: Dict[str, Any] = {}

class EvidencePack(BaseModel):
    items: List[EvidenceItem]

class TraversalSpec(BaseModel):
    direction: str = "OUTBOUND"
    minDepth: int = 1
    maxDepth: int = 1
    edgeCollection: Optional[str] = None

class GraphNode(BaseModel):
    id: str
    key: str
    label: str
    properties: Dict[str, Any]

class TraversalRequest(BaseModel):
    clientId: str
    projectId: Optional[str] = None
    startKey: str
    spec: TraversalSpec

class CrawlRequest(BaseModel):
    url: str
    maxDepth: int = 2
    allowExternalDomains: bool = False
    clientId: Optional[str] = None
    projectId: Optional[str] = None


# === Full Ingest Models (document + attachments) ===

class AttachmentMetadata(BaseModel):
    """Metadata for a single attachment"""
    filename: str
    contentType: Optional[str] = None
    size: int = 0


class AttachmentResult(BaseModel):
    """Result of processing a single attachment"""
    filename: str
    status: str  # "success", "failed", "skipped"
    contentType: str
    extractedText: Optional[str] = None
    error: Optional[str] = None


class FullIngestRequest(BaseModel):
    """Request for full document ingestion with attachments"""
    clientId: str
    projectId: Optional[str] = None
    sourceUrn: str
    sourceType: str = ""  # "email", "confluence", "jira", "git", etc.
    subject: Optional[str] = None  # Email subject, page title, issue key
    content: str  # Main text content
    metadata: Dict[str, Any] = {}
    observedAt: datetime = Field(default_factory=datetime.now)
    # Attachments are sent separately via multipart form


class FullIngestResult(BaseModel):
    """Result of full document ingestion"""
    status: str
    chunks_count: int
    nodes_created: int
    edges_created: int
    attachments_processed: int
    attachments_failed: int
    # Summary for routing decision
    summary: str  # 2-3 sentence summary of content
    entities: List[str] = []  # Key entities found (people, projects, etc.)
    hasActionableContent: bool = False  # Hint for router
    suggestedActions: List[str] = []  # e.g., ["reply_email", "review_code"]

