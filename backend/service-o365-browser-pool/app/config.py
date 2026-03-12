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

    model_config = {"env_prefix": "O365_POOL_", "env_file": ".env", "extra": "ignore"}


settings = Settings()
