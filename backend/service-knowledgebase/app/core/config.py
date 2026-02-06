from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    OLLAMA_BASE_URL: str = "http://192.168.100.117:11434"
    OLLAMA_EMBEDDING_BASE_URL: str = "http://192.168.100.117:11436"
    WEAVIATE_URL: str = "http://192.168.100.117:8080"
    WEAVIATE_GRPC_URL: str = "192.168.100.117:50051"
    ARANGO_URL: str = "http://192.168.100.117:8529"
    ARANGO_DB: str = "jervis"
    ARANGO_USER: str = "root"
    ARANGO_PASSWORD: str = ""

    TIKA_URL: str = "http://192.168.100.117:8081"
    JOERN_URL: str = "http://192.168.100.117:8082"

    EMBEDDING_MODEL: str = "qwen3-embedding:8b"
    LLM_MODEL: str = "qwen2.5:14b"
    VISION_MODEL: str = "qwen3-vl:latest"

    # OCR-first image processing configuration
    OCR_TEXT_THRESHOLD: int = 100  # Minimum characters for OCR to be considered sufficient
    OCR_PRINTABLE_RATIO: float = 0.85  # Minimum ratio of printable characters (0.0-1.0)

    class Config:
        env_file = ".env"


settings = Settings()
