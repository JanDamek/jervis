from app.db.arango import get_arango_db
from app.api.models import IngestRequest, TraversalRequest, GraphNode, TraversalSpec
from langchain_ollama import ChatOllama
from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.core.config import settings
import json
import asyncio

class GraphService:
    def __init__(self):
        self.db = get_arango_db()
        self.llm = ChatOllama(
            base_url=settings.OLLAMA_BASE_URL,
            model=settings.LLM_MODEL,
            format="json",
            temperature=0
        )
        self.text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=2000,
            chunk_overlap=200
        )
        self._ensure_schema()

    def _ensure_schema(self):
        if not self.db.has_collection("KnowledgeNodes"):
            self.db.create_collection("KnowledgeNodes")
        if not self.db.has_collection("KnowledgeEdges"):
            self.db.create_collection("KnowledgeEdges", edge=True)

    async def ingest(self, request: IngestRequest) -> tuple[int, int]:
        # 1. Split text into chunks for processing
        chunks = self.text_splitter.split_text(request.content)
        
        nodes_created = 0
        edges_created = 0
        
        # Process chunks in parallel (limited concurrency) or sequentially
        # For simplicity and rate limiting, we'll do sequential or small batches
        for chunk in chunks:
            n, e = await self._process_chunk(chunk, request)
            nodes_created += n
            edges_created += e
            
        return nodes_created, edges_created

    async def _process_chunk(self, text: str, request: IngestRequest) -> tuple[int, int]:
        prompt = f"""
        You are a knowledge graph extractor. Extract entities and relationships from the text.
        Return a JSON object with two keys: "nodes" and "edges".
        "nodes": list of objects with "label" (name), "type" (category), "description".
        "edges": list of objects with "source" (label of source node), "target" (label of target node), "relation" (type of relationship).
        
        Text: {text}
        """
        
        try:
            response = await self.llm.ainvoke(prompt)
            content = response.content
            # Clean up markdown code blocks if present
            if "```json" in content:
                content = content.split("```json")[1].split("```")[0]
            elif "```" in content:
                content = content.split("```")[1].split("```")[0]
                
            data = json.loads(content)
        except Exception as e:
            print(f"LLM extraction failed: {e}")
            return 0, 0

        local_nodes = 0
        local_edges = 0

        # 2. Deduplicate and Insert Nodes
        for node in data.get("nodes", []):
            # Simple deduplication by Label (normalized)
            key = node['label'].strip().replace(" ", "_").replace("/", "_")
            # Sanitize key
            key = "".join(c for c in key if c.isalnum() or c in "_-")
            
            doc = {
                "_key": key,
                "label": node['label'],
                "type": node.get('type', 'Entity'),
                "description": node.get('description', ''),
                "clientId": request.clientId,
                "projectId": request.projectId or ""
            }
            # Upsert
            if not self.db.collection("KnowledgeNodes").has(key):
                try:
                    self.db.collection("KnowledgeNodes").insert(doc)
                    local_nodes += 1
                except Exception:
                    pass 
            else:
                # Optional: Update description if new one is better
                pass

        # 3. Insert Edges
        for edge in data.get("edges", []):
            source_key = edge['source'].strip().replace(" ", "_").replace("/", "_")
            source_key = "".join(c for c in source_key if c.isalnum() or c in "_-")
            
            target_key = edge['target'].strip().replace(" ", "_").replace("/", "_")
            target_key = "".join(c for c in target_key if c.isalnum() or c in "_-")
            
            relation = edge['relation'].replace(' ', '_')
            relation = "".join(c for c in relation if c.isalnum() or c in "_-")

            edge_key = f"{source_key}_{relation}_{target_key}"
            edge_doc = {
                "_key": edge_key,
                "_from": f"KnowledgeNodes/{source_key}",
                "_to": f"KnowledgeNodes/{target_key}",
                "relation": edge['relation']
            }
            
            if not self.db.collection("KnowledgeEdges").has(edge_key):
                try:
                    # Only insert if both nodes exist (Arango enforces this for edges)
                    # We might need to ensure nodes exist if LLM hallucinated an edge to a non-extracted node
                    if self.db.collection("KnowledgeNodes").has(source_key) and \
                       self.db.collection("KnowledgeNodes").has(target_key):
                        self.db.collection("KnowledgeEdges").insert(edge_doc)
                        local_edges += 1
                except Exception:
                    pass 

        return local_nodes, local_edges

    async def traverse(self, request: TraversalRequest) -> list[GraphNode]:
        # AQL Traversal with Filtering
        
        # Base: General
        filter_expr = "(v.clientId == 'GENERAL' OR v.clientId == null)"
        
        if request.clientId:
            # Client specific logic
            client_expr = f"v.clientId == '{request.clientId}'"
            
            if request.projectId:
                # Project: (Client AND Project) OR (Client AND NoProject)
                project_expr = f"v.projectId == '{request.projectId}'"
                no_project_expr = "(v.projectId == '' OR v.projectId == null)"
                
                client_scope = f"({client_expr} AND ({project_expr} OR {no_project_expr}))"
            else:
                # Client only: Client (regardless of project)
                client_scope = client_expr
            
            filter_expr = f"({filter_expr} OR {client_scope})"
            
        aql = f"""
        FOR v, e IN {request.spec.minDepth}..{request.spec.maxDepth} {request.spec.direction}
        'KnowledgeNodes/{request.startKey}'
        KnowledgeEdges
        FILTER {filter_expr}
        RETURN v
        """
        
        try:
            cursor = self.db.aql.execute(aql)
            nodes = []
            for doc in cursor:
                nodes.append(GraphNode(
                    id=doc["_id"],
                    key=doc["_key"],
                    label=doc.get("label", ""),
                    properties=doc
                ))
            return nodes
        except Exception as e:
            print(f"Traversal failed: {e}")
            return []
