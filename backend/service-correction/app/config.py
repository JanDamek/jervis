"""Configuration for correction service."""

import os


class Settings:
    """Service configuration from environment variables."""

    # KB service URL for storing/retrieving correction rules
    knowledgebase_url: str = os.getenv(
        "KNOWLEDGEBASE_URL",
        "http://service-knowledgebase:8000",
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
