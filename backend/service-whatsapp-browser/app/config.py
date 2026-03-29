"""Configuration for WhatsApp Browser service."""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Settings loaded from environment variables.

    WhatsApp-specific fields use WHATSAPP_ prefix via validation_alias.
    Shared fields (kotlin_server_url, ollama_router_url, mongodb_*) read directly.
    """

    # Service
    host: str = Field(default="0.0.0.0", validation_alias="WHATSAPP_HOST")
    port: int = Field(default=8091, validation_alias="WHATSAPP_PORT")

    # Browser
    profiles_dir: str = Field(default="/browser-profiles", validation_alias="WHATSAPP_PROFILES_DIR")
    headless: bool = Field(default=True, validation_alias="WHATSAPP_HEADLESS")

    # noVNC (only used in headed mode)
    novnc_enabled: bool = Field(default=False, validation_alias="WHATSAPP_NOVNC_ENABLED")
    novnc_port: int = Field(default=6080, validation_alias="WHATSAPP_NOVNC_PORT")
    vnc_password: str = Field(default="", validation_alias="WHATSAPP_VNC_PASSWORD")
    vnc_token_ttl: int = Field(default=300, validation_alias="WHATSAPP_VNC_TOKEN_TTL")
    novnc_external_url: str = Field(
        default="https://jervis-whatsapp-vnc.damek-soft.eu",
        validation_alias="WHATSAPP_NOVNC_EXTERNAL_URL",
    )

    # VLM screen scraping (model selection via ollama-router /route-decision)
    ollama_router_url: str = "http://jervis-ollama-router:11430"

    # QR login monitoring interval (seconds) — only used during login flow
    qr_check_interval: int = Field(default=5, validation_alias="WHATSAPP_QR_CHECK_INTERVAL")

    # Kotlin server callback
    kotlin_server_url: str = "http://jervis-server:5500"

    # MongoDB for scrape result storage
    mongodb_host: str = "nas.lan.mazlusek.com"
    mongodb_port: int = 27017
    mongodb_database: str = "jervis"
    mongodb_user: str = "root"
    mongodb_password: str = ""
    mongodb_auth_db: str = "admin"

    model_config = {"env_file": ".env", "extra": "ignore"}


settings = Settings()
