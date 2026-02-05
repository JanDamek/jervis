from langchain_ollama import ChatOllama
from langchain_core.messages import HumanMessage
from app.core.config import settings
import base64

class ImageService:
    def __init__(self):
        self.llm = ChatOllama(
            base_url=settings.OLLAMA_BASE_URL,
            model=settings.VISION_MODEL,
        )

    async def describe_image(self, image_bytes: bytes) -> str:
        image_b64 = base64.b64encode(image_bytes).decode("utf-8")
        
        # Assuming JPEG/PNG, data URI format is standard for multimodal models
        # We can detect mime type if needed, but generic might work or we assume standard image formats
        
        message = HumanMessage(
            content=[
                {"type": "text", "text": "Describe this image in detail for indexing purposes. Focus on text, diagrams, and key visual elements."},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}},
            ]
        )
        
        response = await self.llm.ainvoke([message])
        return response.content
