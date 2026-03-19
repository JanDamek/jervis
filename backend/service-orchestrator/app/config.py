"""Configuration for the Python Orchestrator service."""

import os

import tiktoken
from pydantic_settings import BaseSettings

_tokenizer = tiktoken.get_encoding("cl100k_base")


class Settings(BaseSettings):
    """Environment-based configuration."""

    # Service
    host: str = "0.0.0.0"
    port: int = 8090

    # MongoDB (persistent checkpointer – same instance as Kotlin server)
    mongodb_url: str = os.getenv("MONGODB_URL", "")

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

    # ArangoDB (direct access for Graph Agent artifact graph)
    arango_url: str = os.getenv("ARANGO_URL", "http://192.168.100.117:8529")
    arango_db: str = os.getenv("ARANGO_DB", "jervis")
    arango_user: str = os.getenv("ARANGO_USER", "root")
    arango_password: str = os.getenv("ARANGO_PASSWORD", "")

    # MCP Server (HTTP – unified KB + environment + orchestrator tools)
    mcp_url: str = os.getenv(
        "MCP_URL", "http://jervis-mcp:8100"
    )
    mcp_api_token: str = os.getenv("MCP_API_TOKEN", "")

    # O365 Gateway (relay auth to Graph API via browser pool tokens)
    o365_gateway_url: str = os.getenv(
        "O365_GATEWAY_URL", "http://jervis-o365-gateway:8080"
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
    openrouter_api_key: str = os.getenv("OPENROUTER_API_KEY", "")
    openrouter_api_base: str = os.getenv("OPENROUTER_API_BASE", "https://openrouter.ai/api/v1")
    claude_code_oauth_token: str = os.getenv("CLAUDE_CODE_OAUTH_TOKEN", "")

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
        "DEFAULT_LARGE_CONTEXT_MODEL", "gemini-3.1-pro"
    )

    # Gemini daily call limit (resets at midnight)
    gemini_daily_limit: int = int(os.getenv("GEMINI_DAILY_LIMIT", "100"))

    # Agent timeouts (seconds)
    agent_timeout_claude: int = 1800
    agent_timeout_kilo: int = 1800

    # Job cleanup
    job_ttl_seconds: int = 300

    # Non-blocking agent dispatch
    max_concurrent_orchestrations: int = 5
    agent_watcher_poll_interval: int = 10  # seconds between job status polls

    # --- Multi-agent delegation system (feature-flagged, all default OFF) ---

    # Feature flags
    use_delegation_graph: bool = False
    use_specialist_agents: bool = False
    use_dag_execution: bool = False
    use_procedural_memory: bool = False
    # use_graph_agent removed — Graph Agent is the sole orchestrator
    # use_memory_agent removed — Memory Agent is always active

    # Delegation settings
    max_delegation_depth: int = 4
    delegation_timeout: int = 300

    # Token budgets per delegation depth
    token_budget_depth_0: int = 48000
    token_budget_depth_1: int = 16000
    token_budget_depth_2: int = 8000
    token_budget_depth_3: int = 4000

    # Context budgeting (ChatContextAssembler)
    total_context_window: int = 48_000        # Model context window (fixed 48k on GPU1)
    system_prompt_reserve: int = 2_000       # Tokens reserved for system prompt + tools
    response_reserve: int = 4_000            # Tokens reserved for LLM response
    recent_message_count: int = 100          # Max recent verbatim messages to load (budget limits actual inclusion)
    max_summary_blocks: int = 15             # Max compressed summary blocks to load
    compress_threshold: int = 20             # Compress when >=N unsummarized messages
    compress_max_retries: int = 2            # Max compression retries on LLM failure
    max_tool_result_in_msg: int = 2_000      # Max chars for tool results in stored messages
    token_estimate_ratio: int = 4            # Chars-per-token ratio (rough, for cs/en)

    # Chat handler constants
    chat_max_iterations: int = 200           # Safety ceiling only — actual stop by stagnation/loop detection
    chat_max_iterations_long: int = 200      # Same — no artificial restriction
    decompose_threshold: int = 8000          # Chars to trigger decomposition (~2k tokens)
    summarize_threshold: int = 16000         # Chars to trigger pre-summarization
    subtopic_max_iterations: int = 3         # Max iterations per sub-topic
    max_subtopics: int = 5                   # Max sub-topics from decomposition

    # Background handler constants
    background_max_iterations: int = 15      # Max agentic loop iterations for background

    # LLM token budgets
    default_output_tokens: int = 4096        # Output token reserve for tier estimation

    # Graph node constants (respond, plan, design)
    respond_max_iterations: int = 8          # Max tool iterations in respond node
    respond_min_answer_chars: int = 40       # W-13: Minimum acceptable answer length
    respond_max_short_retries: int = 1       # W-13: Max retries for short answers
    plan_max_iterations: int = 25            # Max planning iterations
    design_max_iterations: int = 25          # Max design iterations

    # Ollama Router priority header (FOREGROUND = "0" → CRITICAL)
    foreground_priority_level: str = "0"     # Value for X-Ollama-Priority header

    # Streaming
    stream_chunk_size: int = 40              # Chars per fake-streaming chunk (~10 tokens)

    # Guidelines cache
    guidelines_cache_ttl: int = 300          # TTL in seconds for guidelines cache

    # Session memory
    session_memory_ttl_days: int = 7
    session_memory_max_entries: int = 50

    # Memory Agent settings
    lqm_max_warm_entries: int = 1000
    lqm_warm_ttl_seconds: float = 300.0
    lqm_write_buffer_max: int = 500
    affair_max_hot: int = 100
    context_switch_confidence_threshold: float = 0.7

    # Tool execution timeouts (seconds)
    timeout_web_search: float = 15.0
    timeout_kb_search: float = 300.0  # no aggressive timeout — KB handles its own performance
    max_tool_result_chars: int = 8000
    tool_execution_timeout: int = 120

    # Intent Router (Phase 3 — feature-flagged OFF by default)
    use_intent_router: bool = False
    router_max_tokens: int = 256
    router_timeout: float = 5.0
    router_confidence_threshold: float = 0.7
    direct_max_iterations: int = 1
    research_max_iterations: int = 3
    task_mgmt_max_iterations: int = 4
    complex_max_iterations: int = 6
    memory_max_iterations: int = 3

    class Config:
        env_prefix = "ORCHESTRATOR_"


settings = Settings()

# --- Context & LLM limits ---
LOCAL_CONTEXT_LIMIT = 256_000          # qwen3 max context (tokens)
DEFAULT_TIER_CONTEXT = 262_144         # fallback when tier config missing num_ctx
CONTENT_TRUNCATION_CHARS = 200         # in-place content truncation threshold
KB_TIMEOUT_FAST = 10.0                 # dedicated KB endpoints (cached)
KB_TIMEOUT_STANDARD = 15.0             # semantic search via /retrieve


def estimate_tokens(text: str) -> int:
    """Count tokens using tiktoken (cl100k_base encoding).

    Fast (~1ms per call), much more accurate than chars/N heuristic.
    Consistent estimator for all modules.
    """
    return max(1, len(_tokenizer.encode(text)))


def foreground_headers(processing_mode: str) -> dict[str, str]:
    """Return Ollama priority headers for foreground requests.

    FOREGROUND → {"X-Ollama-Priority": "0"} (user is waiting)
    BACKGROUND → {} (router defaults to NORMAL)
    """
    if processing_mode == "FOREGROUND":
        return {"X-Ollama-Priority": settings.foreground_priority_level}
    return {}
