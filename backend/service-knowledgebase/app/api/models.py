from pydantic import BaseModel, Field, model_validator
from typing import Optional, Dict, Any, List
from datetime import datetime


class IngestRequest(BaseModel):
    """
    Request to ingest content into the knowledge base.

    Multi-tenant scoping:
      - clientId="" (empty) = GLOBAL data, visible everywhere
      - clientId="X", projectId="" = CLIENT-LEVEL data, visible to client X
      - clientId="X", projectId="Y" = PROJECT-LEVEL data, visible only to project Y
      - groupId="G" = GROUP-LEVEL data, visible to all projects in group G

    INVARIANT: projectId cannot be set without clientId (project requires client)
    """
    clientId: str = ""  # Empty = global
    projectId: Optional[str] = None  # None/empty = client-level or global
    groupId: Optional[str] = None  # Group for cross-project KB visibility
    sourceUrn: str
    kind: str = ""
    content: str
    metadata: Dict[str, Any] = {}
    observedAt: datetime = Field(default_factory=datetime.now)

    @model_validator(mode='after')
    def validate_tenant_hierarchy(self):
        """Ensure project is not set without client."""
        if self.projectId and not self.clientId:
            raise ValueError("projectId cannot be set without clientId - project requires client")
        return self

class IngestResult(BaseModel):
    status: str
    chunks_count: int
    nodes_created: int
    edges_created: int
    # Bidirectional linking info (optional, for debugging)
    chunk_ids: List[str] = []
    entity_keys: List[str] = []

class RetrievalRequest(BaseModel):
    """
    Request to retrieve evidence from the knowledge base.

    Multi-tenant scoping:
      - clientId="" = retrieve only GLOBAL data
      - clientId="X" = retrieve GLOBAL + CLIENT-LEVEL data for client X
      - clientId="X", projectId="Y" = retrieve GLOBAL + CLIENT-LEVEL + PROJECT data
      - groupId="G" = also include GROUP-LEVEL data for cross-project visibility

    INVARIANT: projectId cannot be set without clientId
    """
    query: str
    clientId: str = ""  # Empty = only global data
    projectId: Optional[str] = None
    groupId: Optional[str] = None  # Group for cross-project KB visibility
    asOf: Optional[datetime] = None
    minConfidence: float = 0.0
    maxResults: int = 10
    # Hybrid retrieval: expand results using graph relationships
    expandGraph: bool = True

    @model_validator(mode='after')
    def validate_tenant_hierarchy(self):
        """Ensure project is not set without client."""
        if self.projectId and not self.clientId:
            raise ValueError("projectId cannot be set without clientId - project requires client")
        return self

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
    """
    Request to traverse the knowledge graph.

    Multi-tenant scoping: same rules as RetrievalRequest
    """
    clientId: str = ""
    projectId: Optional[str] = None
    groupId: Optional[str] = None
    startKey: str
    spec: TraversalSpec

    @model_validator(mode='after')
    def validate_tenant_hierarchy(self):
        """Ensure project is not set without client."""
        if self.projectId and not self.clientId:
            raise ValueError("projectId cannot be set without clientId - project requires client")
        return self

class CrawlRequest(BaseModel):
    """
    Request to crawl and ingest web content.

    Multi-tenant scoping: same rules as IngestRequest
    """
    url: str
    maxDepth: int = 2
    allowExternalDomains: bool = False
    clientId: str = ""  # Empty = global
    projectId: Optional[str] = None
    groupId: Optional[str] = None

    @model_validator(mode='after')
    def validate_tenant_hierarchy(self):
        """Ensure project is not set without client."""
        if self.projectId and not self.clientId:
            raise ValueError("projectId cannot be set without clientId - project requires client")
        return self


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
    """
    Request for full document ingestion with attachments.

    Multi-tenant scoping: same rules as IngestRequest
    """
    clientId: str = ""  # Empty = global
    projectId: Optional[str] = None
    groupId: Optional[str] = None
    sourceUrn: str
    sourceType: str = ""  # "email", "confluence", "jira", "git", etc.
    subject: Optional[str] = None  # Email subject, page title, issue key
    content: str  # Main text content
    metadata: Dict[str, Any] = {}
    observedAt: datetime = Field(default_factory=datetime.now)
    # Attachments are sent separately via multipart form

    @model_validator(mode='after')
    def validate_tenant_hierarchy(self):
        """Ensure project is not set without client."""
        if self.projectId and not self.clientId:
            raise ValueError("projectId cannot be set without clientId - project requires client")
        return self


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


# === Purge Models ===

class PurgeRequest(BaseModel):
    """
    Request to purge all KB data for a given sourceUrn.

    Removes:
    - All Weaviate RAG chunks matching sourceUrn
    - References to those chunks from ArangoDB graph nodes and edges
    - Orphaned nodes/edges that have no remaining evidence
    """
    sourceUrn: str
    clientId: str = ""


class PurgeResult(BaseModel):
    """Result of a purge operation."""
    status: str
    chunks_deleted: int = 0
    nodes_cleaned: int = 0
    edges_cleaned: int = 0
    nodes_deleted: int = 0
    edges_deleted: int = 0


# === Advanced Retrieval Models ===

class HybridRetrievalRequest(BaseModel):
    """
    Advanced hybrid retrieval request with configurable options.

    Combines RAG vector search with graph expansion and entity matching.
    """
    query: str
    clientId: str = ""
    projectId: Optional[str] = None
    groupId: Optional[str] = None
    maxResults: int = 10
    minConfidence: float = 0.0

    # Hybrid options
    expandGraph: bool = True           # Enable graph traversal expansion
    extractEntities: bool = True       # Extract entities from query
    useRRF: bool = True                # Use Reciprocal Rank Fusion scoring
    maxGraphHops: int = 2              # Max hops for graph traversal
    maxSeeds: int = 10                 # Max seed nodes for expansion
    diversityFactor: float = 0.7       # Source diversity (0-1, lower = more diverse)

    @model_validator(mode='after')
    def validate_tenant_hierarchy(self):
        if self.projectId and not self.clientId:
            raise ValueError("projectId cannot be set without clientId")
        return self


class HybridEvidenceItem(BaseModel):
    """Evidence item with detailed scoring breakdown."""
    content: str
    combinedScore: float               # Final combined score
    sourceUrn: str

    # Score breakdown
    ragScore: float = 0.0              # Score from vector search
    graphScore: float = 0.0            # Score from graph expansion
    entityScore: float = 0.0           # Score from entity match

    # Provenance
    source: str = "rag"                # "rag", "graph", "entity"
    graphDistance: int = 0             # Hops from seed (if graph)
    graphRefs: List[str] = []          # Referenced entities
    matchedEntity: Optional[str] = None  # If from entity match

    metadata: Dict[str, Any] = {}


class HybridEvidencePack(BaseModel):
    """Evidence pack with detailed results."""
    items: List[HybridEvidenceItem]
    totalFound: int = 0
    queryEntities: List[str] = []      # Entities extracted from query
    seedNodes: List[str] = []          # Seed nodes used for expansion



# === KB Kind Listing Models ===

class ListByKindRequest(BaseModel):
    """Request to list all chunks of a specific kind."""
    clientId: str = ""
    projectId: Optional[str] = None
    kind: str
    maxResults: int = 100

