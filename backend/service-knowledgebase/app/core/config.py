"""
Knowledge Base microservice configuration.

All values can be overridden via environment variables (see K8s ConfigMap:
k8s/configmap.yaml → jervis-knowledgebase-config).

Ollama architecture: see docs/structures.md § "Ollama Instance Architecture".
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):

    # -- Ollama endpoints -------------------------------------------------------
    # All KB requests go through Ollama Router (port 11430) which routes to GPU/CPU based on load.
    # Router automatically assigns: VLM → GPU, embeddings/ingest → CPU.
    # MUST be set via ConfigMap (k8s/configmap.yaml → jervis-knowledgebase-config).
    OLLAMA_BASE_URL: str
    OLLAMA_EMBEDDING_BASE_URL: str
    OLLAMA_INGEST_BASE_URL: str

    # -- Storage backends -------------------------------------------------------
    WEAVIATE_URL: str = "http://192.168.100.117:8080"       # Vector store (RAG)
    WEAVIATE_GRPC_URL: str = "192.168.100.117:50051"        # Weaviate gRPC for fast vector ops
    ARANGO_URL: str = "http://192.168.100.117:8529"         # Knowledge graph
    ARANGO_DB: str = "jervis"
    ARANGO_USER: str = "root"
    ARANGO_PASSWORD: str = ""

    # -- Microservice endpoints -------------------------------------------------
    DOCUMENT_EXTRACTION_URL: str = "http://jervis-document-extraction:8080"  # Dedicated text extraction service
    KOTLIN_SERVER_URL: str = ""                              # Kotlin server for progress callbacks (e.g. http://jervis-server:5500)

    # -- Model configuration ----------------------------------------------------
    EMBEDDING_MODEL: str = "bge-m3"                          # Vector embedding model (568M, 1024d, multilingual)
    LLM_MODEL: str = "qwen3:14b"                             # Graph extraction — complex reasoning, GPU-2 (permanent)
    VISION_MODEL: str = "qwen3-vl-tool:latest"               # VLM for image description (GPU-2, on-demand swap)

    # -- Reranker (TEI / bge-reranker-v2-m3 on CPU) ----------------------------
    RERANKER_URL: str = ""                                    # Empty = disabled. Set to http://jervis-reranker:8080 to enable.
    RERANKER_TOP_K: int = 50                                  # Retrieve this many candidates for reranking
    RERANKER_FINAL_K: int = 10                                # Return this many after reranking

    # -- Contextual chunking ----------------------------------------------------
    CONTEXTUAL_PREFIX_ENABLED: bool = True                    # Prepend LLM-generated context to chunks before embedding
    CONTEXTUAL_PREFIX_MODEL: str = ""                         # Model for context generation (empty = use LLM_MODEL)

    # Ingest model routing — single extraction model on GPU-2.
    # GPU-1 (30b) freed for orchestrator/chat. GPU-2 runs extraction parallel.
    INGEST_MODEL_SIMPLE: str = "qwen3:14b"                   # Link relevance, quick classification (GPU-2, permanent)
    INGEST_MODEL_COMPLEX: str = "qwen3:14b"                  # Summary extraction, complex ingest (GPU-2, permanent)

    # -- Context window management (same pattern as chat/orchestrator) ---------
    # Chat/orchestrator uses: TOTAL_CONTEXT_WINDOW=32768, RESPONSE_RESERVE=4000,
    # TOKEN_ESTIMATE_RATIO=4, _truncate_messages_to_budget().
    # Indexing must do the same to prevent silent truncation / model hangs.
    INGEST_CONTEXT_CAP: int = 32_768         # Max num_ctx (GPU-2: 3 models loaded, limited KV cache budget)
    INGEST_RESPONSE_RESERVE: int = 3_000    # Reserve tokens for JSON response (nodes + edges + thoughts)
    INGEST_PROMPT_RESERVE: int = 1_500      # Reserve tokens for instruction/system part of prompt
    TOKEN_ESTIMATE_RATIO: float = 2.5       # chars / 2.5 ≈ tokens (Czech text heuristic)
    MAX_EXTRACTION_CHUNKS: int = 30         # Max chunks for LLM graph extraction per document
    LLM_CALL_TIMEOUT: float = 86400.0       # ChatOllama wrapper potřebuje float, prakticky neomezeno (24h)

    # -- Image processing -------------------------------------------------------
    # VLM-first strategy: DocumentExtractor uses VLM for images and scanned PDFs.
    # Threshold for hybrid PDF: pages with less text than this → VLM extraction.
    OCR_TEXT_THRESHOLD: int = 100      # Min chars for pymupdf page to skip VLM
    OCR_PRINTABLE_RATIO: float = 0.85  # Min ratio of printable chars (0.0–1.0)

    # -- Deployment mode --------------------------------------------------------
    # Controls which route groups are registered:
    #   "all"   — all endpoints (default, backward compat, local dev)
    #   "read"  — retrieve, search, traverse, alias/resolve, graph, chunks
    #   "write" — ingest, crawl, purge, alias/register, alias/merge
    KB_MODE: str = "all"

    # -- Pod-to-pod gRPC (Phase 2 — KnowledgeMaintenanceService and later) ------
    GRPC_PORT: int = 5501

    # -- Graph traversal limits --------------------------------------------------
    # Prevents OOM kills on high-connectivity hub nodes (user:*, client_id:*, etc.)
    MAX_GRAPH_TRAVERSAL_RESULTS: int = 200   # LIMIT per single AQL traversal
    MAX_GRAPH_EXPANSION_CHUNKS: int = 500    # Total chunk cap in hybrid retriever graph expansion

    # -- Concurrency limits -----------------------------------------------------
    # Read operations: unlimited (as many as pod can handle)
    # Write operations: limited to prevent resource exhaustion (won't block reads)
    MAX_CONCURRENT_READS: int = 1000  # Effectively unlimited (pod capacity limit)
    MAX_CONCURRENT_WRITES: int = 10   # Max parallel write requests (queue others)

    # -- Thought Map maintenance -------------------------------------------------
    THOUGHT_DECAY_FACTOR: float = 0.995
    THOUGHT_MERGE_THRESHOLD: float = 0.92
    THOUGHT_ARCHIVE_THRESHOLD: float = 0.05
    THOUGHT_ARCHIVE_DAYS: int = 30

    # -- Embedding concurrency --------------------------------------------------
    # GPU-2 benchmark (2026-03-04): sweet spot = 4-5 concurrent requests
    # With multi-worker uvicorn, total concurrent = workers × this value
    # READ (4 workers): set to 2 → max 8 concurrent (near GPU sweet spot)
    # WRITE (1 worker): set to 5 → optimal for single process batch embedding
    MAX_CONCURRENT_EMBEDDINGS: int = 5

    class Config:
        env_file = ".env"


settings = Settings()
