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
    TIKA_URL: str = "http://192.168.100.117:8081"           # Apache Tika (document extraction)
    KOTLIN_SERVER_URL: str = ""                              # Kotlin server for progress callbacks (e.g. http://jervis-server:5500)

    # -- Model configuration ----------------------------------------------------
    EMBEDDING_MODEL: str = "qwen3-embedding:8b"             # Vector embedding model (GPU-2, permanent)
    LLM_MODEL: str = "qwen3:14b"                             # Graph extraction — complex reasoning, GPU-2 (permanent)
    VISION_MODEL: str = "qwen3-vl-tool:latest"               # VLM for image description (GPU-2, on-demand swap)

    # Ingest model routing — dual extraction models on GPU-2.
    # GPU-1 (30b) freed for orchestrator/chat. GPU-2 runs extraction parallel.
    INGEST_MODEL_SIMPLE: str = "qwen3:8b"                    # Link relevance, quick classification (GPU-2, ~6GB)
    INGEST_MODEL_COMPLEX: str = "qwen3:14b"                  # Summary extraction, complex ingest (GPU-2, ~11GB)

    # -- Context window management (same pattern as chat/orchestrator) ---------
    # Chat/orchestrator uses: TOTAL_CONTEXT_WINDOW=32768, RESPONSE_RESERVE=4000,
    # TOKEN_ESTIMATE_RATIO=4, _truncate_messages_to_budget().
    # Indexing must do the same to prevent silent truncation / model hangs.
    INGEST_CONTEXT_CAP: int = 32_768         # Max num_ctx (GPU-2: 3 models loaded, limited KV cache budget)
    INGEST_RESPONSE_RESERVE: int = 2_000    # Reserve tokens for JSON response generation
    INGEST_PROMPT_RESERVE: int = 1_500      # Reserve tokens for instruction/system part of prompt
    TOKEN_ESTIMATE_RATIO: float = 2.5       # chars / 2.5 ≈ tokens (Czech text heuristic)
    MAX_EXTRACTION_CHUNKS: int = 30         # Max chunks for LLM graph extraction per document
    LLM_CALL_TIMEOUT: float = 900.0         # Max seconds for a single LLM call (router queue may hold request)

    # -- Image processing -------------------------------------------------------
    # OCR-first strategy: try Tika OCR, fall back to VLM if OCR output is poor.
    OCR_TEXT_THRESHOLD: int = 100      # Min chars for OCR to be considered sufficient
    OCR_PRINTABLE_RATIO: float = 0.85  # Min ratio of printable chars (0.0–1.0)

    # -- Deployment mode --------------------------------------------------------
    # Controls which route groups are registered:
    #   "all"   — all endpoints (default, backward compat, local dev)
    #   "read"  — retrieve, search, traverse, alias/resolve, graph, chunks
    #   "write" — ingest, crawl, purge, alias/register, alias/merge
    KB_MODE: str = "all"

    # -- Graph traversal limits --------------------------------------------------
    # Prevents OOM kills on high-connectivity hub nodes (user:*, client_id:*, etc.)
    MAX_GRAPH_TRAVERSAL_RESULTS: int = 200   # LIMIT per single AQL traversal
    MAX_GRAPH_EXPANSION_CHUNKS: int = 500    # Total chunk cap in hybrid retriever graph expansion

    # -- Concurrency limits -----------------------------------------------------
    # Read operations: unlimited (as many as pod can handle)
    # Write operations: limited to prevent resource exhaustion (won't block reads)
    MAX_CONCURRENT_READS: int = 1000  # Effectively unlimited (pod capacity limit)
    MAX_CONCURRENT_WRITES: int = 10   # Max parallel write requests (queue others)

    # -- Embedding concurrency --------------------------------------------------
    # GPU-2 benchmark (2026-03-04): sweet spot = 4-5 concurrent requests
    # With multi-worker uvicorn, total concurrent = workers × this value
    # READ (4 workers): set to 2 → max 8 concurrent (near GPU sweet spot)
    # WRITE (1 worker): set to 5 → optimal for single process batch embedding
    MAX_CONCURRENT_EMBEDDINGS: int = 5

    class Config:
        env_file = ".env"


settings = Settings()
