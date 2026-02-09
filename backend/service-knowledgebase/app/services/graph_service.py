import json
import asyncio
import logging

from app.db.arango import get_arango_db
from app.api.models import IngestRequest, TraversalRequest, GraphNode, TraversalSpec
from app.services.normalizer import (
    normalize_graph_ref,
    normalize_entity_type,
    build_graph_key
)
from app.services.alias_registry import AliasRegistry
from langchain_ollama import ChatOllama
from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.core.config import settings

logger = logging.getLogger(__name__)


class GraphService:
    def __init__(self):
        self.db = get_arango_db()
        self.alias_registry = AliasRegistry(self.db)
        self.llm = ChatOllama(
            base_url=settings.OLLAMA_INGEST_BASE_URL,
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

    async def ingest(
        self,
        request: IngestRequest,
        chunk_ids: list[str] = None
    ) -> tuple[int, int, list[str]]:
        """
        Ingest content into graph store with bidirectional linking.

        Args:
            request: The ingest request
            chunk_ids: List of RAG chunk UUIDs that contain this content

        Returns:
            Tuple of (nodes_created, edges_created, list of extracted entity keys)
        """
        # 1. Split text into chunks for processing
        chunks = self.text_splitter.split_text(request.content)

        nodes_created = 0
        edges_created = 0
        all_entity_keys = []

        logger.info("Graph ingest: %d text chunks to process via LLM model=%s", len(chunks), settings.LLM_MODEL)

        # Process chunks sequentially (for LLM rate limiting)
        for i, chunk in enumerate(chunks, 1):
            logger.info("Calling LLM for entity extraction chunk %d/%d", i, len(chunks))
            n, e, keys = await self._process_chunk(chunk, request, chunk_ids)
            nodes_created += n
            edges_created += e
            all_entity_keys.extend(keys)

        # Deduplicate entity keys
        all_entity_keys = list(set(all_entity_keys))

        logger.info("Graph ingest done nodes=%d edges=%d entities=%d", nodes_created, edges_created, len(all_entity_keys))
        return nodes_created, edges_created, all_entity_keys

    async def _process_chunk(
        self,
        text: str,
        request: IngestRequest,
        chunk_ids: list[str] = None
    ) -> tuple[int, int, list[str]]:
        """
        Process a single chunk: extract entities and relationships.

        Uses normalization and alias registry for consistent entity keys.

        Returns: (nodes_created, edges_created, entity_keys)
        """
        prompt = f"""You are a knowledge graph extractor. Extract entities and relationships from the text.
Return a JSON object with two keys: "nodes" and "edges".
"nodes": list of objects with:
  - "label": name/identifier of the entity
  - "type": category (e.g., person, file, jira_issue, class, method, concept)
  - "description": brief description

"edges": list of objects with:
  - "source": label of source node (must match a node label)
  - "target": label of target node (must match a node label)
  - "relation": type of relationship (e.g., mentions, contains, calls, assigned_to)

Guidelines:
- Extract ALL mentioned entities: people, systems, files, tickets, code elements, concepts
- Use specific types: person, jira_issue, confluence_page, file, class, method, commit, etc.
- For people, use their full name or identifier
- For tickets, include the key (e.g., "TASK-123")
- Keep labels concise but specific

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
            logger.warning("LLM extraction failed: %s", e)
            return 0, 0, []

        local_nodes = 0
        local_edges = 0
        entity_keys = []
        label_to_key = {}  # Map original labels to canonical keys
        nodes_collection = self.db.collection("KnowledgeNodes")
        edges_collection = self.db.collection("KnowledgeEdges")

        # 2. Process Nodes with normalization and alias resolution
        for node in data.get("nodes", []):
            label = node.get('label', '').strip()
            if not label:
                continue

            # Normalize entity type
            entity_type = normalize_entity_type(node.get('type', 'entity'))

            # Build normalized key: type:label
            raw_key = build_graph_key(entity_type, label)
            if not raw_key:
                continue

            # Resolve through alias registry (may return different canonical key)
            canonical_key = await self.alias_registry.resolve(request.clientId, raw_key)

            # Register this raw_key → canonical_key mapping
            await self.alias_registry.register(request.clientId, raw_key, canonical_key)

            # Store mapping for edge resolution
            label_to_key[label.lower()] = canonical_key
            entity_keys.append(canonical_key)

            # Use canonical key for node storage
            # Extract just the value part for ArangoDB key (remove colons)
            arango_key = canonical_key.replace(":", "__")

            if nodes_collection.has(arango_key):
                # Node exists - append chunk IDs to ragChunks (merge)
                if chunk_ids:
                    try:
                        existing = nodes_collection.get(arango_key)
                        existing_chunks = set(existing.get("ragChunks", []))
                        new_chunks = existing_chunks | set(chunk_ids)
                        # Also update description if new one is longer
                        new_desc = node.get('description', '')
                        old_desc = existing.get('description', '')
                        update_doc = {
                            "_key": arango_key,
                            "ragChunks": list(new_chunks)
                        }
                        if len(new_desc) > len(old_desc):
                            update_doc["description"] = new_desc
                        nodes_collection.update(update_doc)
                    except Exception:
                        pass
            else:
                # Create new node with ragChunks
                doc = {
                    "_key": arango_key,
                    "canonicalKey": canonical_key,  # Store original namespace:value format
                    "label": label,
                    "type": entity_type,
                    "description": node.get('description', ''),
                    "clientId": request.clientId,
                    "projectId": request.projectId or "",
                    "groupId": request.groupId or "",
                    "ragChunks": chunk_ids or [],
                }
                try:
                    nodes_collection.insert(doc)
                    local_nodes += 1
                except Exception:
                    pass

        # 3. Process Edges with evidence tracking
        for edge in data.get("edges", []):
            source_label = edge.get('source', '').strip().lower()
            target_label = edge.get('target', '').strip().lower()
            relation = edge.get('relation', '').strip()

            if not (source_label and target_label and relation):
                continue

            # Resolve labels to canonical keys
            source_key = label_to_key.get(source_label)
            target_key = label_to_key.get(target_label)

            if not source_key or not target_key:
                continue

            # Convert to ArangoDB keys
            source_arango = source_key.replace(":", "__")
            target_arango = target_key.replace(":", "__")

            # Normalize relation
            relation_normalized = normalize_graph_ref(relation)

            edge_key = f"{source_arango}_{relation_normalized}_{target_arango}"

            # Check if both nodes exist
            if not (nodes_collection.has(source_arango) and nodes_collection.has(target_arango)):
                continue

            if edges_collection.has(edge_key):
                # Edge exists - append chunk IDs to evidenceChunkIds (merge)
                if chunk_ids:
                    try:
                        existing = edges_collection.get(edge_key)
                        existing_evidence = set(existing.get("evidenceChunkIds", []))
                        new_evidence = existing_evidence | set(chunk_ids)
                        edges_collection.update({
                            "_key": edge_key,
                            "evidenceChunkIds": list(new_evidence)
                        })
                    except Exception:
                        pass
            else:
                # Create new edge with evidence
                edge_doc = {
                    "_key": edge_key,
                    "_from": f"KnowledgeNodes/{source_arango}",
                    "_to": f"KnowledgeNodes/{target_arango}",
                    "relation": relation,
                    "relationNormalized": relation_normalized,
                    "evidenceChunkIds": chunk_ids or [],
                }
                try:
                    edges_collection.insert(edge_doc)
                    local_edges += 1
                except Exception:
                    pass

        return local_nodes, local_edges, entity_keys

    async def purge_chunk_refs(self, deleted_chunk_ids: list[str]) -> tuple[int, int, int, int]:
        """
        Remove references to deleted RAG chunks from graph nodes and edges.
        Delete orphaned nodes/edges that have no remaining evidence.

        Returns:
            Tuple of (nodes_cleaned, edges_cleaned, nodes_deleted, edges_deleted)
        """
        if not deleted_chunk_ids:
            return 0, 0, 0, 0

        deleted_set = set(deleted_chunk_ids)
        nodes_collection = self.db.collection("KnowledgeNodes")
        edges_collection = self.db.collection("KnowledgeEdges")

        nodes_cleaned = 0
        edges_cleaned = 0
        nodes_deleted = 0
        edges_deleted = 0

        # Clean nodes: remove deleted chunk IDs from ragChunks
        try:
            cursor = self.db.aql.execute(
                "FOR doc IN KnowledgeNodes FILTER LENGTH(doc.ragChunks) > 0 RETURN doc"
            )
            for doc in cursor:
                rag_chunks = doc.get("ragChunks", [])
                remaining = [c for c in rag_chunks if c not in deleted_set]
                if len(remaining) < len(rag_chunks):
                    if remaining:
                        nodes_collection.update({"_key": doc["_key"], "ragChunks": remaining})
                        nodes_cleaned += 1
                    else:
                        # No remaining evidence - delete node
                        try:
                            nodes_collection.delete(doc["_key"])
                            nodes_deleted += 1
                        except Exception:
                            pass
        except Exception as e:
            logger.warning("Failed to clean node chunk refs: %s", e)

        # Clean edges: remove deleted chunk IDs from evidenceChunkIds
        try:
            cursor = self.db.aql.execute(
                "FOR doc IN KnowledgeEdges FILTER LENGTH(doc.evidenceChunkIds) > 0 RETURN doc"
            )
            for doc in cursor:
                evidence_ids = doc.get("evidenceChunkIds", [])
                remaining = [c for c in evidence_ids if c not in deleted_set]
                if len(remaining) < len(evidence_ids):
                    if remaining:
                        edges_collection.update({"_key": doc["_key"], "evidenceChunkIds": remaining})
                        edges_cleaned += 1
                    else:
                        # No remaining evidence - delete edge
                        try:
                            edges_collection.delete(doc["_key"])
                            edges_deleted += 1
                        except Exception:
                            pass
        except Exception as e:
            logger.warning("Failed to clean edge chunk refs: %s", e)

        logger.info("Graph purge: nodes_cleaned=%d nodes_deleted=%d edges_cleaned=%d edges_deleted=%d",
                     nodes_cleaned, nodes_deleted, edges_cleaned, edges_deleted)
        return nodes_cleaned, edges_cleaned, nodes_deleted, edges_deleted

    async def get_node_chunks(self, node_key: str, client_id: str = "") -> list[str]:
        """
        Get RAG chunk IDs associated with a graph node.

        The key is resolved through alias registry.
        Used for retrieving evidence text for a node.
        """
        try:
            # Resolve key through alias registry
            canonical_key = await self.alias_registry.resolve(client_id, node_key)
            arango_key = canonical_key.replace(":", "__")

            nodes_collection = self.db.collection("KnowledgeNodes")
            if nodes_collection.has(arango_key):
                doc = nodes_collection.get(arango_key)
                return doc.get("ragChunks", [])
            return []
        except Exception:
            return []

    async def get_edge_evidence(self, edge_key: str) -> list[str]:
        """
        Get RAG chunk IDs that provide evidence for an edge.

        Used for verifying and explaining relationships.
        """
        try:
            edges_collection = self.db.collection("KnowledgeEdges")
            if edges_collection.has(edge_key):
                doc = edges_collection.get(edge_key)
                return doc.get("evidenceChunkIds", [])
            return []
        except Exception:
            return []

    async def traverse(self, request: TraversalRequest) -> list[GraphNode]:
        # AQL Traversal with Multi-tenant Filtering
        #
        # Visibility rules:
        #   - clientId="" or NULL = GLOBAL, visible everywhere
        #   - clientId="X", projectId="" or NULL = CLIENT-LEVEL, visible to client X
        #   - clientId="X", projectId="Y" = PROJECT-LEVEL, visible only to project Y
        #
        # Query filter pattern (using bind parameters for security):
        #   (clientId == "" OR clientId == NULL OR clientId == @clientId)
        #   AND (projectId == "" OR projectId == NULL OR projectId == @projectId)

        # Resolve startKey through alias registry
        canonical_key = await self.alias_registry.resolve(request.clientId, request.startKey)
        arango_key = canonical_key.replace(":", "__")

        bind_vars = {
            "startNode": f"KnowledgeNodes/{arango_key}",
            "minDepth": request.spec.minDepth,
            "maxDepth": request.spec.maxDepth,
        }

        # Build filter expression with bind parameters
        if request.clientId:
            client_expr = "(v.clientId == '' OR v.clientId == null OR v.clientId == @clientId)"
            bind_vars["clientId"] = request.clientId
        else:
            # No client = only global data
            client_expr = "(v.clientId == '' OR v.clientId == null)"

        if request.projectId:
            if request.groupId:
                # Group cross-visibility: include project's own data + other projects in same group
                project_expr = (
                    "(v.projectId == '' OR v.projectId == null "
                    "OR v.projectId == @projectId "
                    "OR v.groupId == @groupId)"
                )
                bind_vars["projectId"] = request.projectId
                bind_vars["groupId"] = request.groupId
            else:
                project_expr = "(v.projectId == '' OR v.projectId == null OR v.projectId == @projectId)"
                bind_vars["projectId"] = request.projectId
            filter_expr = f"({client_expr} AND {project_expr})"
        else:
            # No project = don't filter by project
            filter_expr = client_expr

        # Note: direction and depth must be static in AQL, cannot be bind vars
        # Validate direction to prevent injection
        direction = request.spec.direction.upper()
        if direction not in ("OUTBOUND", "INBOUND", "ANY"):
            direction = "OUTBOUND"

        aql = f"""
        FOR v, e IN @minDepth..@maxDepth {direction}
        @startNode
        KnowledgeEdges
        FILTER {filter_expr}
        RETURN v
        """

        try:
            cursor = self.db.aql.execute(aql, bind_vars=bind_vars)
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
            logger.warning("Traversal failed: %s", e)
            return []

    async def get_node(self, key: str, client_id: str = "", project_id: str = None, group_id: str = None) -> GraphNode | None:
        """
        Get a single node by key with multi-tenant filtering.

        The key is resolved through alias registry to find canonical key.
        Returns None if node doesn't exist or is not accessible.
        """
        # Resolve key through alias registry
        canonical_key = await self.alias_registry.resolve(client_id, key)
        arango_key = canonical_key.replace(":", "__")

        bind_vars = {"key": arango_key}

        # Build filter for multi-tenant access
        if client_id:
            client_expr = "(doc.clientId == '' OR doc.clientId == null OR doc.clientId == @clientId)"
            bind_vars["clientId"] = client_id
        else:
            client_expr = "(doc.clientId == '' OR doc.clientId == null)"

        if project_id:
            if group_id:
                project_expr = (
                    "(doc.projectId == '' OR doc.projectId == null "
                    "OR doc.projectId == @projectId "
                    "OR doc.groupId == @groupId)"
                )
                bind_vars["projectId"] = project_id
                bind_vars["groupId"] = group_id
            else:
                project_expr = "(doc.projectId == '' OR doc.projectId == null OR doc.projectId == @projectId)"
                bind_vars["projectId"] = project_id
            filter_expr = f"({client_expr} AND {project_expr})"
        else:
            filter_expr = client_expr

        aql = f"""
        FOR doc IN KnowledgeNodes
        FILTER doc._key == @key AND {filter_expr}
        RETURN doc
        """

        try:
            cursor = self.db.aql.execute(aql, bind_vars=bind_vars)
            docs = list(cursor)
            if docs:
                doc = docs[0]
                return GraphNode(
                    id=doc["_id"],
                    key=doc["_key"],
                    label=doc.get("label", ""),
                    properties=doc
                )
            return None
        except Exception as e:
            logger.warning("Get node failed: %s", e)
            return None

    async def search_nodes(
        self,
        query: str,
        client_id: str = "",
        project_id: str = None,
        group_id: str = None,
        node_type: str = None,
        limit: int = 20
    ) -> list[GraphNode]:
        """
        Search nodes by label with multi-tenant filtering.
        """
        bind_vars = {
            "query": f"%{query.lower()}%",
            "limit": limit
        }

        # Build filter for multi-tenant access
        if client_id:
            client_expr = "(doc.clientId == '' OR doc.clientId == null OR doc.clientId == @clientId)"
            bind_vars["clientId"] = client_id
        else:
            client_expr = "(doc.clientId == '' OR doc.clientId == null)"

        if project_id:
            if group_id:
                project_expr = (
                    "(doc.projectId == '' OR doc.projectId == null "
                    "OR doc.projectId == @projectId "
                    "OR doc.groupId == @groupId)"
                )
                bind_vars["projectId"] = project_id
                bind_vars["groupId"] = group_id
            else:
                project_expr = "(doc.projectId == '' OR doc.projectId == null OR doc.projectId == @projectId)"
                bind_vars["projectId"] = project_id
            filter_expr = f"({client_expr} AND {project_expr})"
        else:
            filter_expr = client_expr

        # Optional type filter
        if node_type:
            filter_expr += " AND doc.type == @nodeType"
            bind_vars["nodeType"] = node_type

        aql = f"""
        FOR doc IN KnowledgeNodes
        FILTER LOWER(doc.label) LIKE @query AND {filter_expr}
        LIMIT @limit
        RETURN doc
        """

        try:
            cursor = self.db.aql.execute(aql, bind_vars=bind_vars)
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
            logger.warning("Search nodes failed: %s", e)
            return []
