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
    # Knowledge Base write endpoint (separate deployment for write operations)
    knowledgebase_write_url: str = os.getenv(
        "KNOWLEDGEBASE_WRITE_URL", "http://jervis-knowledgebase-write:8080"
    )

    # SearXNG web search (runs on port 30053)
    searxng_url: str = os.getenv(
        "SEARXNG_URL", "http://192.168.100.117:30053"
    )

    # LLM providers
    # Ollama Router endpoint - MUST be set via ConfigMap (k8s/configmap.yaml)
    ollama_url: str = os.getenv("OLLAMA_API_BASE")
    anthropic_api_key: str = os.getenv("ANTHROPIC_API_KEY", "")
    openai_api_key: str = os.getenv("OPENAI_API_KEY", "")
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
    default_correction_model: str = os.getenv(
        "DEFAULT_CORRECTION_MODEL", "qwen3-coder-tool:30b"
    )
    default_cloud_model: str = os.getenv(
        "DEFAULT_CLOUD_MODEL", "claude-sonnet-4-5-20250929"
    )
    default_premium_model: str = os.getenv(
        "DEFAULT_PREMIUM_MODEL", "claude-opus-4-6"
    )
    default_openai_model: str = os.getenv(
        "DEFAULT_OPENAI_MODEL", "gpt-4o"
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

    # Non-blocking agent dispatch
    max_concurrent_orchestrations: int = 5
    agent_watcher_poll_interval: int = 10  # seconds between job status polls
    agent_heartbeat_timeout: int = 300  # seconds — consider agent dead if no progress

    # --- Multi-agent delegation system (feature-flagged, all default OFF) ---

    # Feature flags
    use_delegation_graph: bool = False
    use_specialist_agents: bool = False
    use_dag_execution: bool = False
    use_procedural_memory: bool = False
    # use_memory_agent removed — Memory Agent is always active

    # Delegation settings
    max_delegation_depth: int = 4
    delegation_timeout: int = 300

    # Token budgets per delegation depth
    token_budget_depth_0: int = 48000
    token_budget_depth_1: int = 16000
    token_budget_depth_2: int = 8000
    token_budget_depth_3: int = 4000

    # Session memory
    session_memory_ttl_days: int = 7
    session_memory_max_entries: int = 50

    # Memory Agent settings
    lqm_max_warm_entries: int = 1000
    lqm_warm_ttl_seconds: float = 300.0
    lqm_write_buffer_max: int = 500
    affair_max_hot: int = 100
    context_switch_confidence_threshold: float = 0.7

    class Config:
        env_prefix = "ORCHESTRATOR_"


settings = Settings()
