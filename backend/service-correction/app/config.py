"""Configuration for correction service."""

import os


class Settings:
    """Service configuration from environment variables."""

    # KB service URL for reading correction rules (retrieve, search, list)
    knowledgebase_url: str = os.getenv(
        "KNOWLEDGEBASE_URL",
        "http://jervis-knowledgebase:8080",
    )

    # KB service URL for writing correction rules (ingest, purge)
    # Split deployment: read replicas don't expose write endpoints
    knowledgebase_write_url: str = os.getenv(
        "KNOWLEDGEBASE_WRITE_URL",
        "http://jervis-knowledgebase-write:8080",
    )

    # Ollama URL for LLM calls
    ollama_url: str = os.getenv(
        "OLLAMA_URL",
        "http://ollama-router.jervis.svc.cluster.local:11434",
    )

    # Default correction model (qwen3-coder-tool:30b)
    default_correction_model: str = os.getenv(
        "DEFAULT_CORRECTION_MODEL",
        "qwen3-coder-tool:30b",
    )

    # Kotlin server URL for progress callbacks
    kotlin_server_url: str = os.getenv(
        "KOTLIN_SERVER_URL",
        "http://server-service:8080",
    )


settings = Settings()
