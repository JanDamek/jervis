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

    # Pod identity (StatefulSet — each connection gets its own pod)
    pod_name: str = Field(default="jervis-o365-browser-pool-0", validation_alias="POD_NAME")
    statefulset_service: str = Field(
        default="jervis-o365-browser-pool",
        validation_alias="O365_POOL_STATEFULSET_SERVICE",
    )
    k8s_namespace: str = Field(default="jervis", validation_alias="O365_POOL_NAMESPACE")

    @property
    def pod_ordinal(self) -> int:
        """Extract ordinal from pod name (e.g. 'jervis-o365-browser-pool-2' → 2)."""
        try:
            return int(self.pod_name.rsplit("-", 1)[-1])
        except (ValueError, IndexError):
            return 0

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
