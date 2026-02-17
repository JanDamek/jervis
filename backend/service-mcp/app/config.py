"""Configuration for Jervis MCP Server."""

from __future__ import annotations

from urllib.parse import quote_plus

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Settings loaded from environment variables."""

    # Service
    host: str = "0.0.0.0"
    port: int = 8100

    # Authentication – Bearer token required for all MCP connections
    mcp_api_tokens: str = ""  # Comma-separated list of valid tokens

    # MongoDB (direct read access for queries)
    mongodb_host: str = "192.168.100.117"
    mongodb_port: int = 27017
    mongodb_user: str = "root"
    mongodb_password: str = "password"
    mongodb_database: str = "jervis"

    # Knowledge Base service (read instance – high priority)
    knowledgebase_url: str = "http://jervis-knowledgebase:8080"
    knowledgebase_write_url: str = "http://jervis-knowledgebase-write:8080"

    # Kotlin server internal API
    kotlin_server_url: str = "http://jervis-server:5500"

    # Default tenant context (can be overridden per-tool call)
    default_client_id: str = ""
    default_project_id: str = ""

    @property
    def mongodb_url(self) -> str:
        pwd = quote_plus(self.mongodb_password)
        return (
            f"mongodb://{self.mongodb_user}:{pwd}"
            f"@{self.mongodb_host}:{self.mongodb_port}"
            f"/{self.mongodb_database}?authSource=admin"
        )

    @property
    def valid_tokens(self) -> set[str]:
        if not self.mcp_api_tokens:
            return set()
        return {t.strip() for t in self.mcp_api_tokens.split(",") if t.strip()}

    model_config = {"env_prefix": "MCP_", "env_file": ".env", "extra": "ignore"}


settings = Settings()
