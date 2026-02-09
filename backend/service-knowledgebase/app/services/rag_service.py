import logging
import uuid

from langchain_ollama import OllamaEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.core.config import settings
from app.db.weaviate import get_weaviate_client
from app.api.models import IngestRequest, RetrievalRequest, EvidenceItem, EvidencePack
import weaviate.classes.config as wvc
import weaviate.classes.query as wvq

logger = logging.getLogger(__name__)

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
                vectorizer_config=wvc.Configure.Vectorizer.none(),  # We provide vectors
                properties=[
                    wvc.Property(name="content", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="sourceUrn", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="clientId", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="projectId", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="kind", data_type=wvc.DataType.TEXT),
                    # Bidirectional linking: list of graph node keys referenced in this chunk
                    wvc.Property(name="groupId", data_type=wvc.DataType.TEXT),
                    wvc.Property(name="graphRefs", data_type=wvc.DataType.TEXT_ARRAY),
                ]
            )

    async def ingest(
        self,
        request: IngestRequest,
        graph_refs: list[str] = None
    ) -> tuple[int, list[str]]:
        """
        Ingest content into RAG store.

        Args:
            request: The ingest request
            graph_refs: Optional list of graph node keys referenced in this content

        Returns:
            Tuple of (chunk_count, list of chunk UUIDs)
        """
        chunks = self.text_splitter.split_text(request.content)
        collection = self.client.collections.get("KnowledgeChunk")

        chunk_ids = []
        with collection.batch.dynamic() as batch:
            for chunk in chunks:
                chunk_id = str(uuid.uuid4())
                vector = self.embeddings.embed_query(chunk)
                batch.add_object(
                    uuid=chunk_id,
                    properties={
                        "content": chunk,
                        "sourceUrn": request.sourceUrn,
                        "clientId": request.clientId,
                        "projectId": request.projectId or "",
                        "groupId": request.groupId or "",
                        "kind": request.kind or "",
                        "graphRefs": graph_refs or [],
                    },
                    vector=vector
                )
                chunk_ids.append(chunk_id)

        return len(chunk_ids), chunk_ids

    async def update_chunk_graph_refs(self, chunk_id: str, graph_refs: list[str]) -> bool:
        """
        Update graphRefs for an existing chunk (bidirectional linking).

        Called after graph extraction to link chunks to discovered entities.
        """
        try:
            collection = self.client.collections.get("KnowledgeChunk")
            collection.data.update(
                uuid=chunk_id,
                properties={"graphRefs": graph_refs}
            )
            return True
        except Exception as e:
            logger.warning("Failed to update chunk graphRefs: %s", e)
            return False

    async def get_chunks_by_ids(self, chunk_ids: list[str]) -> list[dict]:
        """
        Fetch chunks by their UUIDs.

        Used for evidence retrieval from graph nodes.
        """
        collection = self.client.collections.get("KnowledgeChunk")
        results = []

        for chunk_id in chunk_ids:
            try:
                obj = collection.query.fetch_object_by_id(chunk_id)
                if obj:
                    results.append({
                        "id": chunk_id,
                        "content": obj.properties.get("content", ""),
                        "sourceUrn": obj.properties.get("sourceUrn", ""),
                        "graphRefs": obj.properties.get("graphRefs", []),
                    })
            except Exception:
                pass

        return results

    async def purge_by_source(self, source_urn: str) -> tuple[int, list[str]]:
        """
        Delete all RAG chunks matching a sourceUrn.

        Returns:
            Tuple of (chunks_deleted, list of deleted chunk UUIDs)
        """
        collection = self.client.collections.get("KnowledgeChunk")

        # Find all chunks with this sourceUrn
        response = collection.query.fetch_objects(
            filters=wvq.Filter.by_property("sourceUrn").equal(source_urn),
            limit=10000,
            return_properties=["sourceUrn"],
        )

        deleted_ids = []
        for obj in response.objects:
            chunk_id = str(obj.uuid)
            try:
                collection.data.delete_by_id(obj.uuid)
                deleted_ids.append(chunk_id)
            except Exception as e:
                logger.warning("Failed to delete chunk %s: %s", chunk_id, e)

        logger.info("Purged %d RAG chunks for sourceUrn=%s", len(deleted_ids), source_urn)
        return len(deleted_ids), deleted_ids

    async def list_by_kind(
        self,
        client_id: str,
        project_id: str | None,
        kind: str,
        limit: int = 100,
    ) -> list[dict]:
        """List all chunks matching a specific kind, with tenant filtering."""
        collection = self.client.collections.get("KnowledgeChunk")

        kind_filter = wvq.Filter.by_property("kind").equal(kind)

        global_client = wvq.Filter.by_property("clientId").equal("")
        if client_id:
            my_client = wvq.Filter.by_property("clientId").equal(client_id)
            client_filter = wvq.Filter.any_of([global_client, my_client])
        else:
            client_filter = global_client

        if project_id:
            global_project = wvq.Filter.by_property("projectId").equal("")
            my_project = wvq.Filter.by_property("projectId").equal(project_id)
            project_filter = wvq.Filter.any_of([global_project, my_project])
            tenant_filter = wvq.Filter.all_of([client_filter, project_filter])
        else:
            tenant_filter = client_filter

        filters = wvq.Filter.all_of([kind_filter, tenant_filter])

        response = collection.query.fetch_objects(
            filters=filters,
            limit=limit,
            return_properties=["content", "sourceUrn", "clientId", "projectId", "kind"],
        )

        results = []
        for obj in response.objects:
            results.append({
                "content": obj.properties.get("content", ""),
                "sourceUrn": obj.properties.get("sourceUrn", ""),
                "metadata": obj.properties,
            })
        return results

    async def retrieve(self, request: RetrievalRequest) -> EvidencePack:
        vector = self.embeddings.embed_query(request.query)
        collection = self.client.collections.get("KnowledgeChunk")

        # Multi-tenant Filtering Logic
        #
        # Visibility rules:
        #   - clientId="" (empty) = GLOBAL, visible everywhere
        #   - clientId="X", projectId="" = CLIENT-LEVEL, visible to client X and all its projects
        #   - clientId="X", projectId="Y" = PROJECT-LEVEL, visible only to project Y
        #
        # Query filter pattern:
        #   (clientId == "" OR clientId == requested_client)
        #   AND (projectId == "" OR projectId == requested_project)  -- only if project specified
        #
        # This ensures:
        #   - Global data (client="", project="") is always included
        #   - Client-level data (client=X, project="") is included for that client
        #   - Project data (client=X, project=Y) is included only for that project

        # Client filter: always include global ("") + requested client
        global_client = wvq.Filter.by_property("clientId").equal("")

        if request.clientId:
            my_client = wvq.Filter.by_property("clientId").equal(request.clientId)
            client_filter = wvq.Filter.any_of([global_client, my_client])
        else:
            # No client specified = only global data
            client_filter = global_client

        # Project filter: only if project is specified
        if request.projectId:
            # Include global project ("") + requested project
            global_project = wvq.Filter.by_property("projectId").equal("")
            my_project = wvq.Filter.by_property("projectId").equal(request.projectId)
            project_alternatives = [global_project, my_project]

            # Group cross-visibility: include data from other projects in same group
            if request.groupId:
                my_group = wvq.Filter.by_property("groupId").equal(request.groupId)
                project_alternatives.append(my_group)

            project_filter = wvq.Filter.any_of(project_alternatives)

            # Combine: client AND project
            filters = wvq.Filter.all_of([client_filter, project_filter])
        else:
            # No project specified = don't filter by project (return all projects for client)
            filters = client_filter
        
        response = collection.query.near_vector(
            near_vector=vector,
            limit=request.maxResults,
            filters=filters,
            return_metadata=wvq.MetadataQuery(distance=True)
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
