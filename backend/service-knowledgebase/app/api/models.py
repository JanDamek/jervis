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
    # Scheduling hints (for three-way routing in qualifier)
    hasFutureDeadline: bool = False  # Content mentions a future deadline
    suggestedDeadline: Optional[str] = None  # ISO-8601 datetime string
    isAssignedToMe: bool = False  # Content is assigned to the owning client/team
    urgency: str = "normal"  # "urgent" | "normal" | "low"


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


# === Git Structural Ingest Models ===

class GitFileInfo(BaseModel):
    """Metadata for a single file in a git repository."""
    path: str
    extension: str = ""
    language: str = ""
    sizeBytes: int = 0


class GitBranchInfo(BaseModel):
    """Metadata for a git branch."""
    name: str
    isDefault: bool = False
    status: str = "active"  # "active", "merged", "stale"
    lastCommitHash: str = ""


class GitClassInfo(BaseModel):
    """Class/type extracted from source code via tree-sitter."""
    name: str
    qualifiedName: str = ""
    filePath: str
    visibility: str = "public"
    isInterface: bool = False
    methods: List[str] = []  # Method names


class GitFileContent(BaseModel):
    """Source code content for a single file (for tree-sitter parsing)."""
    path: str
    content: str


class GitStructureIngestRequest(BaseModel):
    """
    Request for direct structural ingest of git repository.

    Bypasses LLM entity extraction — creates graph nodes directly
    from structured repository data (files, branches, classes).

    fileContents: Source code content for tree-sitter parsing.
    When provided, KB service invokes tree-sitter to extract classes, methods,
    and imports — creating richer graph nodes (method, has_method, imports edges).

    Multi-tenant scoping: same rules as IngestRequest.
    """
    clientId: str
    projectId: str
    repositoryIdentifier: str  # e.g., "myorg/myrepo"
    branch: str  # The branch being indexed
    defaultBranch: str = "main"
    branches: List[GitBranchInfo] = []
    files: List[GitFileInfo] = []
    classes: List[GitClassInfo] = []  # From tree-sitter analysis (or empty if fileContents provided)
    fileContents: List[GitFileContent] = []  # Source code for tree-sitter parsing
    metadata: Dict[str, Any] = {}

    @model_validator(mode='after')
    def validate_tenant_hierarchy(self):
        if not self.clientId:
            raise ValueError("clientId is required for git structure ingest")
        if not self.projectId:
            raise ValueError("projectId is required for git structure ingest")
        return self


class GitStructureIngestResult(BaseModel):
    """Result of git structural ingest."""
    status: str
    nodes_created: int = 0
    edges_created: int = 0
    nodes_updated: int = 0
    repository_key: str = ""
    branch_key: str = ""
    files_indexed: int = 0
    classes_indexed: int = 0
    methods_indexed: int = 0


# === Joern CPG Ingest Models ===

class CpgIngestRequest(BaseModel):
    """
    Request to import Joern CPG export into knowledge graph.

    Called after Joern K8s Job completes. The cpgData contains pruned CPG
    with methods, types, calls, and typeRefs from Joern analysis.

    Multi-tenant scoping: same rules as GitStructureIngestRequest.
    """
    clientId: str
    projectId: str
    branch: str
    workspacePath: str  # Path to project on PVC (for Joern execution)

    @model_validator(mode='after')
    def validate_tenant_hierarchy(self):
        if not self.clientId:
            raise ValueError("clientId is required for CPG ingest")
        if not self.projectId:
            raise ValueError("projectId is required for CPG ingest")
        return self


class CpgIngestResult(BaseModel):
    """Result of Joern CPG ingest."""
    status: str
    methods_enriched: int = 0
    extends_edges: int = 0
    calls_edges: int = 0
    uses_type_edges: int = 0


# === Git Commit Ingest Models ===

class GitCommitInfo(BaseModel):
    """Single git commit metadata."""
    hash: str
    message: str
    author: str
    date: str
    branch: str
    parent_hash: Optional[str] = None
    files_modified: List[str] = []
    files_created: List[str] = []
    files_deleted: List[str] = []


class GitCommitIngestRequest(BaseModel):
    """Request to ingest structured git commit data into KB graph.

    Creates commit nodes in ArangoDB with edges to branch and file nodes.
    Optional diff_content is ingested as RAG chunks for fulltext search.

    Multi-tenant scoping: same rules as GitStructureIngestRequest.
    """
    clientId: str
    projectId: str
    repositoryIdentifier: str
    branch: str
    commits: List[GitCommitInfo]
    diff_content: Optional[str] = None

    @model_validator(mode='after')
    def validate_tenant_hierarchy(self):
        if not self.clientId:
            raise ValueError("clientId is required for git commit ingest")
        if not self.projectId:
            raise ValueError("projectId is required for git commit ingest")
        return self


class GitCommitIngestResult(BaseModel):
    """Result of git commit ingest."""
    status: str
    commits_ingested: int = 0
    nodes_created: int = 0
    edges_created: int = 0
    rag_chunks: int = 0


# === Joern Quick Scan Models ===

class JoernScanRequest(BaseModel):
    """
    Request to run a pre-built Joern analysis scan.

    Available scan types:
    - security: SQL injection, command injection, hardcoded secrets
    - dataflow: HTTP input sources and sensitive sinks
    - callgraph: Method fan-out analysis, dead code detection
    - complexity: Cyclomatic complexity, long method detection
    """
    scanType: str  # "security"|"dataflow"|"callgraph"|"complexity"
    clientId: str
    projectId: str
    workspacePath: str  # Path to project on PVC

    @model_validator(mode='after')
    def validate_scan_type(self):
        valid_types = {"security", "dataflow", "callgraph", "complexity"}
        if self.scanType not in valid_types:
            raise ValueError(f"scanType must be one of: {', '.join(valid_types)}")
        return self


class JoernScanResult(BaseModel):
    """Result of Joern quick scan."""
    status: str  # "success" | "error"
    scanType: str
    output: str  # Scan findings (stdout from Joern)
    warnings: Optional[str] = None  # stderr from Joern
    exitCode: int = 0

