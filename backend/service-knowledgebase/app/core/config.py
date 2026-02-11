"""
Knowledge Base microservice configuration.

All values can be overridden via environment variables (see K8s ConfigMap:
k8s/configmap.yaml → jervis-knowledgebase-config).

Ollama architecture: see docs/structures.md § "Ollama Instance Architecture".
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):

    # -- Ollama endpoints -------------------------------------------------------
    # GPU instance – reserved for orchestrator (30B coding model).
    # KB uses this ONLY for VLM (image_service.py) – all other KB tasks use CPU URLs below.
    OLLAMA_BASE_URL: str = "http://192.168.100.117:11434"
    # CPU instance – embedding + ingest LLM merged on single port.
    # Runs OLLAMA_NUM_PARALLEL=10, OLLAMA_NUM_THREADS=18, OLLAMA_MAX_LOADED_MODELS=3.
    OLLAMA_EMBEDDING_BASE_URL: str = "http://192.168.100.117:11435"
    # CPU instance for ingest LLM calls (summary generation, link relevance check).
    # Same physical Ollama instance as embedding.
    OLLAMA_INGEST_BASE_URL: str = "http://192.168.100.117:11435"

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

    class Config:
        env_file = ".env"


settings = Settings()
