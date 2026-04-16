"""Configuration for O365 Browser Pool service."""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Settings loaded from environment variables.

    Pool-specific fields use O365_POOL_ prefix via validation_alias.
    Shared fields (kotlin_server_url, ollama_router_url, mongodb_*) read directly.
    """

    # Service
    host: str = Field(default="0.0.0.0", validation_alias="O365_POOL_HOST")
    port: int = Field(default=8090, validation_alias="O365_POOL_PORT")

    # Browser pool
    profiles_dir: str = Field(default="/browser-profiles", validation_alias="O365_POOL_PROFILES_DIR")
    max_contexts: int = Field(default=10, validation_alias="O365_POOL_MAX_CONTEXTS")
    headless: bool = Field(default=True, validation_alias="O365_POOL_HEADLESS")

    # Token expiry estimation (seconds)
    token_ttl: int = Field(default=3600, validation_alias="O365_POOL_TOKEN_TTL")  # 1 hour for Graph API tokens
    skype_token_ttl: int = Field(default=86400, validation_alias="O365_POOL_SKYPE_TOKEN_TTL")  # 24 hours for Skype tokens

    # Pod identity — each connection gets its own dynamic Deployment
    # CONNECTION_ID is the MongoDB ObjectId of the ConnectionDocument
    connection_id: str = Field(default="", validation_alias="CONNECTION_ID")
    k8s_namespace: str = Field(default="jervis", validation_alias="O365_POOL_NAMESPACE")

    # noVNC (only used in headed mode)
    novnc_enabled: bool = Field(default=False, validation_alias="O365_POOL_NOVNC_ENABLED")
    novnc_port: int = Field(default=6080, validation_alias="O365_POOL_NOVNC_PORT")
    vnc_password: str = Field(default="", validation_alias="O365_POOL_VNC_PASSWORD")  # Auto-generated if empty (read from /tmp/vnc_password)
    vnc_token_ttl: int = Field(default=300, validation_alias="O365_POOL_VNC_TOKEN_TTL")  # One-time VNC token TTL in seconds (5 min)
    novnc_external_url: str = Field(default="https://jervis-vnc.damek-soft.eu", validation_alias="O365_POOL_NOVNC_EXTERNAL_URL")

    # VLM screen scraping
    ollama_router_url: str = "http://jervis-ollama-router:11430"
    openrouter_api_key: str = Field(default="", validation_alias="O365_POOL_OPENROUTER_API_KEY")
    scraper_chat_interval: int = Field(default=300, validation_alias="O365_POOL_SCRAPER_CHAT_INTERVAL")  # 5 min
    scraper_email_interval: int = Field(default=900, validation_alias="O365_POOL_SCRAPER_EMAIL_INTERVAL")  # 15 min
    scraper_calendar_interval: int = Field(default=1800, validation_alias="O365_POOL_SCRAPER_CALENDAR_INTERVAL")  # 30 min

    # Meeting recording — screenshot cadence for slide/presentation capture
    meeting_screenshot_interval: int = Field(default=30, validation_alias="O365_POOL_MEETING_SCREENSHOT_INTERVAL")  # 30s

    # Meeting WebM pipeline (product §10a)
    meeting_fps: int = Field(default=5, validation_alias="O365_POOL_MEETING_FPS")
    meeting_chunk_seconds: int = Field(default=10, validation_alias="O365_POOL_MEETING_CHUNK_SECONDS")
    meeting_chunk_dir: str = Field(default="/browser-profiles/meeting-chunks", validation_alias="O365_POOL_MEETING_CHUNK_DIR")
    meeting_upload_poll_seconds: int = Field(default=3, validation_alias="O365_POOL_MEETING_UPLOAD_POLL_S")
    meeting_upload_retry_seconds: int = Field(default=2, validation_alias="O365_POOL_MEETING_UPLOAD_RETRY_S")

    # Meeting end-detection thresholds (product §10a)
    meeting_prestart_wait_min: int = Field(default=15, validation_alias="O365_POOL_MEETING_PRESTART_WAIT_MIN")
    meeting_late_arrival_alone_min: int = Field(default=1, validation_alias="O365_POOL_MEETING_LATE_ARRIVAL_ALONE_MIN")
    meeting_alone_after_activity_min: int = Field(default=2, validation_alias="O365_POOL_MEETING_ALONE_AFTER_ACTIVITY_MIN")
    meeting_user_alone_notify_wait_min: int = Field(default=5, validation_alias="O365_POOL_MEETING_USER_ALONE_NOTIFY_WAIT_MIN")

    # Agent LLM context budget (product §3b)
    context_trim_tokens: int = Field(default=12000, validation_alias="O365_POOL_CONTEXT_TRIM_TOKENS")
    context_system_cap: int = Field(default=6000, validation_alias="O365_POOL_CONTEXT_SYSTEM_CAP")
    context_max_msgs: int = Field(default=100, validation_alias="O365_POOL_CONTEXT_MAX_MSGS")
    context_max_tokens: int = Field(default=40000, validation_alias="O365_POOL_CONTEXT_MAX_TOKENS")

    # Pod watcher (background sensor, product §10a)
    watcher_interval_seconds: int = Field(default=2, validation_alias="O365_POOL_WATCHER_INTERVAL_S")

    # Kotlin server callback (for MFA/session notifications)
    kotlin_server_url: str = "http://jervis-server:5500"

    # MongoDB for scrape result storage
    mongodb_host: str = "192.168.100.117"
    mongodb_port: int = 27017
    mongodb_database: str = "jervis"
    mongodb_user: str = "root"
    mongodb_password: str = ""
    mongodb_auth_db: str = "admin"

    model_config = {"env_file": ".env", "extra": "ignore"}


settings = Settings()
