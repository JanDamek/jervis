import httpx
from app.core.config import settings
from pydantic import BaseModel
from typing import Optional
import logging

logger = logging.getLogger(__name__)

class JoernQueryDto(BaseModel):
    query: str
    projectZipBase64: Optional[str] = None

class JoernResultDto(BaseModel):
    stdout: str
    stderr: Optional[str] = None
    exitCode: int

class JoernClient:
    def __init__(self):
        self.base_url = settings.JOERN_URL

    async def run(self, query: str, project_zip_base64: Optional[str] = None) -> JoernResultDto:
        payload = {
            "query": query,
            "projectZipBase64": project_zip_base64
        }
        
        async with httpx.AsyncClient() as client:
            try:
                response = await client.post(f"{self.base_url}/api/joern/run", json=payload, timeout=300.0)
                response.raise_for_status()
                return JoernResultDto(**response.json())
            except Exception as e:
                logger.error(f"Joern execution failed: {e}")
                raise e
