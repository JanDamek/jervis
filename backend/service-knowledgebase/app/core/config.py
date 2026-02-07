from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    OLLAMA_BASE_URL: str = "http://192.168.100.117:11434"
    OLLAMA_EMBEDDING_BASE_URL: str = "http://192.168.100.117:11436"
    # Dedicated CPU Ollama instance for ingest LLM calls (summary, relevance).
    # Runs on separate port with OLLAMA_NUM_PARALLEL=10, OLLAMA_NUM_THREADS=18.
    # Falls back to OLLAMA_BASE_URL if not set.
    OLLAMA_INGEST_BASE_URL: str = "http://192.168.100.117:11435"
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

    # Ingest model routing: simple tasks (relevance check) use 7B,
    # complex tasks (summary generation with entity extraction) use 14B.
    INGEST_MODEL_SIMPLE: str = "qwen2.5:7b"
    INGEST_MODEL_COMPLEX: str = "qwen2.5:14b"

    # OCR-first image processing configuration
    OCR_TEXT_THRESHOLD: int = 100  # Minimum characters for OCR to be considered sufficient
    OCR_PRINTABLE_RATIO: float = 0.85  # Minimum ratio of printable characters (0.0-1.0)

    class Config:
        env_file = ".env"


settings = Settings()
