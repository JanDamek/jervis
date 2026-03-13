"""Configuration for O365 Browser Pool service."""

from __future__ import annotations

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Settings loaded from environment variables with O365_POOL_ prefix."""

    # Service
    host: str = "0.0.0.0"
    port: int = 8090

    # Browser pool
    profiles_dir: str = "/browser-profiles"
    max_contexts: int = 10
    headless: bool = True

    # Token expiry estimation (seconds)
    token_ttl: int = 3600  # 1 hour for Graph API tokens
    skype_token_ttl: int = 86400  # 24 hours for Skype tokens

    # noVNC (only used in headed mode)
    novnc_enabled: bool = False
    novnc_port: int = 6080
    vnc_password: str = ""  # Auto-generated if empty (read from /tmp/vnc_password)
    vnc_token_ttl: int = 300  # One-time VNC token TTL in seconds (5 min)
    novnc_external_url: str = "https://jervis-vnc.damek-soft.eu"

    # VLM screen scraping
    ollama_router_url: str = "http://jervis-ollama-router:11430"
    openrouter_api_key: str = ""
    scraper_chat_interval: int = 300  # 5 min
    scraper_email_interval: int = 900  # 15 min
    scraper_calendar_interval: int = 1800  # 30 min

    # Kotlin server callback (for MFA/session notifications)
    kotlin_server_url: str = "http://jervis-server:8080"

    # MongoDB for scrape result storage
    mongodb_host: str = "192.168.100.117"
    mongodb_port: int = 27017
    mongodb_database: str = "jervis"
    mongodb_username: str = "root"
    mongodb_password: str = ""
    mongodb_auth_db: str = "admin"

    model_config = {"env_prefix": "O365_POOL_", "env_file": ".env", "extra": "ignore"}


settings = Settings()
