"""Configuration for Document Extraction Service."""

import os


class Settings:
    OLLAMA_ROUTER_URL = os.getenv("OLLAMA_ROUTER_URL", "http://jervis-ollama-router:8080")
    PORT = int(os.getenv("PORT", "8080"))
    LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")


settings = Settings()
