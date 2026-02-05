import httpx
from app.core.config import settings
import base64
import logging

logger = logging.getLogger(__name__)

class TikaClient:
    def __init__(self):
        self.base_url = settings.TIKA_URL

    async def process_file(self, file_bytes: bytes, filename: str) -> str:
        data_base64 = base64.b64encode(file_bytes).decode("utf-8")
        
        # Construct payload for Kotlinx Serialization (Sealed Class)
        # Assuming "type" discriminator with simple class name "FileBytes"
        payload = {
            "source": {
                "type": "FileBytes", 
                "fileName": filename,
                "dataBase64": data_base64
            },
            "includeMetadata": False
        }
        
        async with httpx.AsyncClient() as client:
            try:
                response = await client.post(f"{self.base_url}/api/tika/process", json=payload, timeout=60.0)
                response.raise_for_status()
                result = response.json()
                return result.get("plainText", "")
            except Exception as e:
                logger.error(f"Tika processing failed: {e}")
                # Fallback: return empty string or raise
                raise e

    async def process_text(self, text: str) -> str:
        # Tika is primarily for files. If we have text, we assume it's already extracted.
        return text
