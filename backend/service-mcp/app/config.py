"""Configuration for Jervis MCP Server."""

from __future__ import annotations

from urllib.parse import quote_plus

from pydantic import Field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Settings loaded from environment variables."""

    # Service (MCP-specific, keep MCP_ prefix)
    host: str = Field(default="0.0.0.0", validation_alias="MCP_HOST")
    port: int = Field(default=8100, validation_alias="MCP_PORT")

    # Authentication – Bearer token required for all MCP connections (MCP-specific)
    mcp_api_tokens: str = Field(default="", validation_alias="MCP_API_TOKENS")

    # OAuth 2.1 (Google IdP for Claude.ai / iOS connectors) – shared, no prefix
    google_client_id: str = ""
    google_client_secret: str = ""
    oauth_issuer: str = "https://jervis-mcp.damek-soft.eu"
    oauth_allowed_emails: str = ""  # Comma-separated whitelist
    oauth_token_expiry: int = 3600  # 1 hour
    oauth_refresh_expiry: int = 2592000  # 30 days

    # MongoDB (direct read access for queries) – shared, no prefix
    mongodb_host: str = "192.168.100.117"
    mongodb_port: int = 27017
    mongodb_user: str = "root"
    mongodb_password: str = "password"
    mongodb_database: str = "jervis"

    # Knowledge Base service (read instance – high priority) – shared, no prefix
    knowledgebase_url: str = "http://jervis-knowledgebase:8080"
    knowledgebase_write_url: str = "http://jervis-knowledgebase-write:8080"

    # Kotlin server internal API – shared, no prefix
    kotlin_server_url: str = "http://jervis-server:5500"

    # O365 Gateway service – shared, no prefix
    o365_gateway_url: str = "http://jervis-o365-gateway:8080"

    # Kibana / Elasticsearch (cluster log search) – shared, no prefix
    # Default = in-cluster Service DNS in the `logging` namespace.
    kibana_url: str = "http://kibana.logging.svc.cluster.local:5601"

    # Default tenant context (MCP-specific, keep MCP_ prefix)
    default_client_id: str = Field(default="", validation_alias="MCP_DEFAULT_CLIENT_ID")
    default_project_id: str = Field(default="", validation_alias="MCP_DEFAULT_PROJECT_ID")

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

    model_config = {"env_file": ".env", "extra": "ignore"}


settings = Settings()
