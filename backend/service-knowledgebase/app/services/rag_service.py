from langchain_ollama import OllamaEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.core.config import settings
from app.db.weaviate import get_weaviate_client
from app.api.models import IngestRequest, RetrievalRequest, EvidenceItem, EvidencePack
import weaviate.classes.config as wvc

class RagService:
    def __init__(self):
        self.embeddings = OllamaEmbeddings(
            base_url=settings.OLLAMA_EMBEDDING_BASE_URL,
            model=settings.EMBEDDING_MODEL
        )
        self.text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=1000,
            chunk_overlap=200
        )
        self.client = get_weaviate_client()
        self._ensure_schema()

    def _ensure_schema(self):
        if not self.client.collections.exists("KnowledgeChunk"):
            self.client.collections.create(
                name="KnowledgeChunk",
                vectorizer_config=wvc.Configure.Vectorizer.none(), # We provide vectors
                properties=[
                    wvc.Property(name="content", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="sourceUrn", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="clientId", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="projectId", data_type=wvc.DataType.TEXT),
                ]
            )

    async def ingest(self, request: IngestRequest) -> int:
        chunks = self.text_splitter.split_text(request.content)
        collection = self.client.collections.get("KnowledgeChunk")
        
        count = 0
        with collection.batch.dynamic() as batch:
            for chunk in chunks:
                vector = self.embeddings.embed_query(chunk)
                batch.add_object(
                    properties={
                        "content": chunk,
                        "sourceUrn": request.sourceUrn,
                        "clientId": request.clientId,
                        "projectId": request.projectId or "",
                    },
                    vector=vector
                )
                count += 1
        return count

    async def retrieve(self, request: RetrievalRequest) -> EvidencePack:
        vector = self.embeddings.embed_query(request.query)
        collection = self.client.collections.get("KnowledgeChunk")
        
        # Filtering Logic
        # 1. General knowledge is always included (clientId == "GENERAL")
        # 2. If clientId is provided, include that client's data
        # 3. If projectId is provided, include that project's data AND client's data without project
        
        filters = wvc.query.Filter.by_property("clientId").equal("GENERAL")
        
        if request.clientId:
            client_filter = wvc.query.Filter.by_property("clientId").equal(request.clientId)
            
            if request.projectId:
                # Project provided: General OR (Client & NoProject) OR (Client & Project)
                # Assuming empty string for no project as per ingest
                project_filter = wvc.query.Filter.by_property("projectId").equal(request.projectId)
                no_project_filter = wvc.query.Filter.by_property("projectId").equal("")
                
                # (Client AND NoProject) OR (Client AND Project)
                client_specific = wvc.query.Filter.all_of([
                    client_filter,
                    wvc.query.Filter.any_of([project_filter, no_project_filter])
                ])
                
                filters = wvc.query.Filter.any_of([filters, client_specific])
            else:
                # Client provided (no project): General OR Client
                filters = wvc.query.Filter.any_of([filters, client_filter])
        
        response = collection.query.near_vector(
            near_vector=vector,
            limit=request.maxResults,
            filters=filters,
            return_metadata=wvc.MetadataQuery(distance=True)
        )
        
        items = []
        for obj in response.objects:
            items.append(EvidenceItem(
                content=obj.properties["content"],
                score=1.0 - (obj.metadata.distance or 0.0), 
                sourceUrn=obj.properties["sourceUrn"],
                metadata=obj.properties
            ))
            
        return EvidencePack(items=items)
