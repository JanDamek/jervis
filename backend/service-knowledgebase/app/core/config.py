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

    # -- Model configuration ----------------------------------------------------
    EMBEDDING_MODEL: str = "qwen3-embedding:8b"             # Vector embedding model (CPU instance)
    LLM_MODEL: str = "qwen2.5:14b"                          # Default LLM for general KB tasks
    VISION_MODEL: str = "qwen3-vl:latest"                   # VLM for image description

    # Ingest model routing (CPU instance, see docs/structures.md § "Model Routing"):
    # - Simple: binary classification tasks (link relevance) → fast 7B model
    # - Complex: structured JSON output (summary + entity extraction) → accurate 14B model
    INGEST_MODEL_SIMPLE: str = "qwen2.5:7b"
    INGEST_MODEL_COMPLEX: str = "qwen2.5:14b"

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

    # -- Concurrency limits -----------------------------------------------------
    # Read operations: unlimited (as many as pod can handle)
    # Write operations: limited to prevent resource exhaustion (won't block reads)
    MAX_CONCURRENT_READS: int = 1000  # Effectively unlimited (pod capacity limit)
    MAX_CONCURRENT_WRITES: int = 10   # Max parallel write requests (queue others)

    class Config:
        env_file = ".env"


settings = Settings()
