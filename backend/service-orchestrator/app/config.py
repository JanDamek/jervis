"""Configuration for the Python Orchestrator service."""

import os
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Environment-based configuration."""

    # Service
    host: str = "0.0.0.0"
    port: int = 8090

    # MongoDB (persistent checkpointer – same instance as Kotlin server)
    mongodb_url: str = os.getenv(
        "MONGODB_URL",
        "mongodb://root:qusre5-mYfpox-dikpef@192.168.100.117:27017/jervis?authSource=admin",
    )

    # Kotlin Server (internal API – runs on port 5500)
    kotlin_server_url: str = os.getenv(
        "KOTLIN_SERVER_URL", "http://jervis-server:5500"
    )

    # Knowledge Base (runs on port 8080)
    knowledgebase_url: str = os.getenv(
        "KNOWLEDGEBASE_URL", "http://jervis-knowledgebase:8080"
    )

    # LLM providers
    ollama_url: str = os.getenv("OLLAMA_URL", "http://192.168.100.117:11434")
    anthropic_api_key: str = os.getenv("ANTHROPIC_API_KEY", "")
    google_api_key: str = os.getenv("GOOGLE_API_KEY", "")

    # K8s
    k8s_namespace: str = os.getenv("K8S_NAMESPACE", "jervis")
    container_registry: str = os.getenv(
        "CONTAINER_REGISTRY", "registry.damek-soft.eu/jandamek"
    )

    # Workspace
    data_root: str = os.getenv("DATA_ROOT", "/opt/jervis/data")

    # Defaults
    default_local_model: str = os.getenv(
        "DEFAULT_LOCAL_MODEL", "qwen3-coder-tool:30b"
    )
    default_cloud_model: str = os.getenv(
        "DEFAULT_CLOUD_MODEL", "claude-sonnet-4-5-20250929"
    )
    default_premium_model: str = os.getenv(
        "DEFAULT_PREMIUM_MODEL", "claude-opus-4-6"
    )
    default_large_context_model: str = os.getenv(
        "DEFAULT_LARGE_CONTEXT_MODEL", "gemini-2.5-pro"
    )

    # Agent timeouts (seconds)
    agent_timeout_aider: int = 600
    agent_timeout_openhands: int = 1800
    agent_timeout_claude: int = 1800
    agent_timeout_junie: int = 1200

    # Job cleanup
    job_ttl_seconds: int = 300

    class Config:
        env_prefix = "ORCHESTRATOR_"


settings = Settings()
