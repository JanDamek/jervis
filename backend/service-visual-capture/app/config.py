"""Configuration for the visual capture service (pydantic-settings)."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """All values can be overridden via environment variables (ConfigMap / Secret)."""

    # ── RTSP stream ──────────────────────────────────────────────────
    visual_capture_rtsp_url: str = ""
    visual_capture_interval_s: int = 5
    visual_capture_jpeg_quality: int = 85
    visual_capture_enabled: bool = True

    # ── ONVIF PTZ ────────────────────────────────────────────────────
    visual_capture_onvif_host: str = ""
    visual_capture_onvif_port: int = 80
    visual_capture_onvif_user: str = "admin"
    visual_capture_onvif_pass: str = ""

    # ── VLM ──────────────────────────────────────────────────────────
    ollama_router_url: str = "http://jervis-ollama-router:11430"
    visual_capture_vlm_model: str = "qwen3-vl-tool:latest"
    visual_capture_vlm_prompt_scene: str = (
        "Analyze this office camera frame. "
        "1) Extract ALL visible text (OCR) — from monitors, papers, whiteboards, sticky notes. "
        "2) Describe what is on each visible screen/monitor. "
        "3) Note any diagrams, charts, or handwritten content. "
        "Be concise, structured, factual. Czech or English output OK."
    )
    visual_capture_vlm_prompt_whiteboard: str = (
        "This image shows a whiteboard or paper notes. "
        "Extract ALL text exactly as written, preserving structure (bullet points, arrows, boxes). "
        "Describe any diagrams or drawings. Output in the language of the text."
    )
    visual_capture_vlm_prompt_screen: str = (
        "This image shows a computer monitor/screen. "
        "Extract ALL visible text (code, UI elements, document content, chat messages). "
        "Identify the application being used. Be precise with code/technical content."
    )

    # ── Kotlin server ────────────────────────────────────────────────
    kotlin_server_url: str = "http://jervis-server:5500"
    internal_auth_token: str = ""

    # ── Service ──────────────────────────────────────────────────────
    service_port: int = 8096

    class Config:
        env_file = ".env"
        case_sensitive = False


settings = Settings()
