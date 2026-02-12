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
        # Skip LLM entity extraction for simple content types (meeting transcripts, corrections)
        # These are better served by RAG-only retrieval without graph overhead
        skip_llm_kinds = {"meeting", "correction", "whisper"}
        if request.kind and request.kind.lower() in skip_llm_kinds:
            logger.info("Graph ingest skipped for kind=%s (LLM extraction disabled for this type)", request.kind)
            return 0, 0, []

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

        entity_keys = []
        label_to_key = {}  # Map original labels to canonical keys

        # Phase 1: Resolve aliases (async) and prepare node data
        resolved_nodes = []
        for node in data.get("nodes", []):
            label = node.get('label', '').strip()
            if not label:
                continue

            entity_type = normalize_entity_type(node.get('type', 'entity'))
            raw_key = build_graph_key(entity_type, label)
            if not raw_key:
                continue

            # Resolve through alias registry (async — must stay outside thread)
            canonical_key = await self.alias_registry.resolve(request.clientId, raw_key)
            await self.alias_registry.register(request.clientId, raw_key, canonical_key)

            label_to_key[label.lower()] = canonical_key
            entity_keys.append(canonical_key)
            resolved_nodes.append((node, label, entity_type, canonical_key))

        # Phase 2: Prepare edge data (pure computation, no DB)
        edge_specs = []
        for edge in data.get("edges", []):
            source_label = edge.get('source', '').strip().lower()
            target_label = edge.get('target', '').strip().lower()
            relation = edge.get('relation', '').strip()
            if not (source_label and target_label and relation):
                continue
            source_key = label_to_key.get(source_label)
            target_key = label_to_key.get(target_label)
            if not source_key or not target_key:
                continue
            edge_specs.append((edge, source_key, target_key, relation))

        # Phase 3: All ArangoDB ops in one thread call
        def _arango_upsert():
            nodes_collection = self.db.collection("KnowledgeNodes")
            edges_collection = self.db.collection("KnowledgeEdges")
            local_nodes = 0
            local_edges = 0

            for node, label, entity_type, canonical_key in resolved_nodes:
                arango_key = canonical_key.replace(":", "__")
                if nodes_collection.has(arango_key):
                    if chunk_ids:
                        try:
                            existing = nodes_collection.get(arango_key)
                            existing_chunks = set(existing.get("ragChunks", []))
                            new_chunks = existing_chunks | set(chunk_ids)
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
                    doc = {
                        "_key": arango_key,
                        "canonicalKey": canonical_key,
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

            for edge, source_key, target_key, relation in edge_specs:
                source_arango = source_key.replace(":", "__")
                target_arango = target_key.replace(":", "__")
                relation_normalized = normalize_graph_ref(relation)
                edge_key = f"{source_arango}_{relation_normalized}_{target_arango}"

                if not (nodes_collection.has(source_arango) and nodes_collection.has(target_arango)):
                    continue

                if edges_collection.has(edge_key):
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

            return local_nodes, local_edges

        local_nodes, local_edges = await asyncio.to_thread(_arango_upsert)
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

        def _purge():
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
                            try:
                                edges_collection.delete(doc["_key"])
                                edges_deleted += 1
                            except Exception:
                                pass
            except Exception as e:
                logger.warning("Failed to clean edge chunk refs: %s", e)

            return nodes_cleaned, edges_cleaned, nodes_deleted, edges_deleted

        nodes_cleaned, edges_cleaned, nodes_deleted, edges_deleted = await asyncio.to_thread(_purge)
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
            # Resolve key through alias registry (async — stays outside thread)
            canonical_key = await self.alias_registry.resolve(client_id, node_key)
            arango_key = canonical_key.replace(":", "__")

            def _fetch():
                nodes_collection = self.db.collection("KnowledgeNodes")
                if nodes_collection.has(arango_key):
                    doc = nodes_collection.get(arango_key)
                    return doc.get("ragChunks", [])
                return []

            return await asyncio.to_thread(_fetch)
        except Exception:
            return []

    async def get_edge_evidence(self, edge_key: str) -> list[str]:
        """
        Get RAG chunk IDs that provide evidence for an edge.

        Used for verifying and explaining relationships.
        """
        def _fetch():
            edges_collection = self.db.collection("KnowledgeEdges")
            if edges_collection.has(edge_key):
                doc = edges_collection.get(edge_key)
                return doc.get("evidenceChunkIds", [])
            return []

        try:
            return await asyncio.to_thread(_fetch)
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

        # Resolve startKey through alias registry (async — stays outside thread)
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
            client_expr = "(v.clientId == '' OR v.clientId == null)"

        if request.projectId:
            if request.groupId:
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
            filter_expr = client_expr

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

        def _execute():
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

        try:
            return await asyncio.to_thread(_execute)
        except Exception as e:
            logger.warning("Traversal failed: %s", e)
            return []

    async def get_node(self, key: str, client_id: str = "", project_id: str = None, group_id: str = None) -> GraphNode | None:
        """
        Get a single node by key with multi-tenant filtering.

        The key is resolved through alias registry to find canonical key.
        Returns None if node doesn't exist or is not accessible.
        """
        # Resolve key through alias registry (async — stays outside thread)
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

        def _execute():
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

        try:
            return await asyncio.to_thread(_execute)
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
        branch_name: str = None,
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

        # Optional branch filter (for branch-scoped nodes: file, class, method)
        if branch_name:
            filter_expr += " AND doc.branchName == @branchName"
            bind_vars["branchName"] = branch_name

        aql = f"""
        FOR doc IN KnowledgeNodes
        FILTER LOWER(doc.label) LIKE @query AND {filter_expr}
        LIMIT @limit
        RETURN doc
        """

        def _execute():
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

        try:
            return await asyncio.to_thread(_execute)
        except Exception as e:
            logger.warning("Search nodes failed: %s", e)
            return []

    # -----------------------------------------------------------------------
    # Structural ingest helpers (git repository structure, no LLM involved)
    # -----------------------------------------------------------------------

    @staticmethod
    def _safe_arango_key(parts: list[str], max_len: int = 200) -> str:
        """Build a safe ArangoDB _key from parts, hashing if too long.

        ArangoDB _key max is 254 bytes. We use 200 as threshold to leave
        room for edge key prefixes that concatenate multiple node keys.
        """
        import hashlib
        key = "__".join(parts)
        key = key.replace(":", "__").replace("/", "_").replace(".", "_").replace("-", "_")
        # Remove non-alphanumeric except underscore
        key = "".join(c for c in key if c.isalnum() or c == "_")
        if len(key) > max_len:
            h = hashlib.sha256(key.encode()).hexdigest()[:16]
            key = key[:max_len - 17] + "_" + h
        return key

    def _make_repo_key(self, repo_identifier: str) -> str:
        return self._safe_arango_key(["repository", repo_identifier])

    def _make_branch_key(self, branch_name: str, project_id: str) -> str:
        return self._safe_arango_key(["branch", branch_name, project_id])

    def _make_file_key(self, file_path: str, branch_name: str, project_id: str) -> str:
        return self._safe_arango_key(["file", file_path, "branch", branch_name, project_id])

    def _make_class_key(self, qualified_name: str, branch_name: str, project_id: str) -> str:
        return self._safe_arango_key(["class", qualified_name, "branch", branch_name, project_id])

    def _make_method_key(self, class_qname: str, method_name: str, branch_name: str, project_id: str) -> str:
        return self._safe_arango_key(["method", class_qname, method_name, "branch", branch_name, project_id])

    async def ingest_git_structure(
        self,
        client_id: str,
        project_id: str,
        repo_identifier: str,
        branch: str,
        default_branch: str,
        branches: list[dict],
        files: list[dict],
        classes: list[dict] = None,
        file_contents: list[dict] = None,
    ) -> dict:
        """Create graph nodes/edges for repository structure. No LLM involved.

        When file_contents are provided, invokes tree-sitter to extract classes,
        methods, and imports — creating richer graph nodes than metadata alone.

        Args:
            client_id: Client ID
            project_id: Project ID
            repo_identifier: Repository identifier (e.g., "myorg/myrepo")
            branch: The branch currently being indexed
            default_branch: The repository's default branch name
            branches: List of branch info dicts {name, isDefault, status, lastCommitHash}
            files: List of file info dicts {path, extension, language, sizeBytes}
            classes: Optional list of class info dicts {name, qualifiedName, filePath, visibility}
            file_contents: Optional list of {path, content} dicts for tree-sitter parsing

        Returns:
            Dict with counts: nodes_created, edges_created, nodes_updated, etc.
        """
        classes = classes or []
        file_contents = file_contents or []

        # --- Tree-sitter parsing: extract classes + methods from source code ---
        parsed_classes = []
        file_imports: dict[str, list[str]] = {}  # file_path -> [import_names]
        if file_contents:
            try:
                from app.services.code_parser import parse_file
                for fc in file_contents:
                    path = fc.get("path", "")
                    content = fc.get("content", "")
                    if not path or not content:
                        continue
                    file_classes = parse_file(path, content)
                    for cls_info in file_classes:
                        parsed_classes.append(cls_info.to_dict())
                    # Collect imports per file
                    if hasattr(file_classes[0] if file_classes else None, 'imports'):
                        pass  # imports collected via parse_file_imports
                    # Try extracting imports
                    try:
                        from app.services.code_parser import parse_file_imports
                        imports = parse_file_imports(path, content)
                        if imports:
                            file_imports[path] = imports
                    except ImportError:
                        pass
                logger.info(
                    "Tree-sitter parsed %d files → %d classes",
                    len(file_contents), len(parsed_classes),
                )
            except Exception as e:
                logger.warning("Tree-sitter parsing failed, using classes from request: %s", e)

        # Use tree-sitter results if available, otherwise fall back to request classes
        effective_classes = parsed_classes if parsed_classes else classes

        repo_key = self._make_repo_key(repo_identifier)
        current_branch_key = self._make_branch_key(branch, project_id)

        def _upsert():
            nodes_col = self.db.collection("KnowledgeNodes")
            edges_col = self.db.collection("KnowledgeEdges")
            created = 0
            updated = 0
            edge_count = 0
            methods_count = 0

            # 1. Upsert repository node
            if not nodes_col.has(repo_key):
                nodes_col.insert({
                    "_key": repo_key,
                    "canonicalKey": f"repository:{repo_identifier}",
                    "label": repo_identifier,
                    "type": "repository",
                    "defaultBranch": default_branch,
                    "clientId": client_id,
                    "projectId": project_id,
                    "groupId": "",
                    "ragChunks": [],
                })
                created += 1
            else:
                nodes_col.update({
                    "_key": repo_key,
                    "defaultBranch": default_branch,
                })
                updated += 1

            # 2. Upsert branch nodes from branch list
            for b in branches:
                b_key = self._make_branch_key(b["name"], project_id)
                if not nodes_col.has(b_key):
                    nodes_col.insert({
                        "_key": b_key,
                        "canonicalKey": f"branch:{b['name']}:{project_id}",
                        "label": b["name"],
                        "type": "branch",
                        "branchName": b["name"],
                        "isDefault": b.get("isDefault", False),
                        "status": b.get("status", "active"),
                        "lastCommitHash": b.get("lastCommitHash", ""),
                        "clientId": client_id,
                        "projectId": project_id,
                        "groupId": "",
                        "ragChunks": [],
                    })
                    created += 1
                else:
                    nodes_col.update({
                        "_key": b_key,
                        "status": b.get("status", "active"),
                        "lastCommitHash": b.get("lastCommitHash", ""),
                    })
                    updated += 1
                # repo -> branch edge
                e_key = self._safe_arango_key([repo_key, "has_branch", b_key])
                if not edges_col.has(e_key):
                    try:
                        edges_col.insert({
                            "_key": e_key,
                            "_from": f"KnowledgeNodes/{repo_key}",
                            "_to": f"KnowledgeNodes/{b_key}",
                            "relation": "has_branch",
                            "relationNormalized": "has_branch",
                            "evidenceChunkIds": [],
                        })
                        edge_count += 1
                    except Exception:
                        pass

            # 3. Upsert current branch node (ensure it exists)
            if not nodes_col.has(current_branch_key):
                nodes_col.insert({
                    "_key": current_branch_key,
                    "canonicalKey": f"branch:{branch}:{project_id}",
                    "label": branch,
                    "type": "branch",
                    "branchName": branch,
                    "isDefault": branch == default_branch,
                    "status": "active",
                    "fileCount": len(files),
                    "clientId": client_id,
                    "projectId": project_id,
                    "groupId": "",
                    "ragChunks": [],
                })
                created += 1
                # repo -> current branch edge
                e_key = self._safe_arango_key([repo_key, "has_branch", current_branch_key])
                if not edges_col.has(e_key):
                    try:
                        edges_col.insert({
                            "_key": e_key,
                            "_from": f"KnowledgeNodes/{repo_key}",
                            "_to": f"KnowledgeNodes/{current_branch_key}",
                            "relation": "has_branch",
                            "relationNormalized": "has_branch",
                            "evidenceChunkIds": [],
                        })
                        edge_count += 1
                    except Exception:
                        pass
            else:
                nodes_col.update({
                    "_key": current_branch_key,
                    "fileCount": len(files),
                    "status": "active",
                })
                updated += 1

            # 4. Upsert file nodes (limit to 500 per branch)
            for f in files[:500]:
                f_key = self._make_file_key(f["path"], branch, project_id)
                if not nodes_col.has(f_key):
                    nodes_col.insert({
                        "_key": f_key,
                        "canonicalKey": f"file:{f['path']}:branch:{branch}:{project_id}",
                        "label": f["path"],
                        "type": "file",
                        "path": f["path"],
                        "extension": f.get("extension", ""),
                        "language": f.get("language", ""),
                        "branchName": branch,
                        "clientId": client_id,
                        "projectId": project_id,
                        "groupId": "",
                        "ragChunks": [],
                    })
                    created += 1
                else:
                    updated += 1
                # branch -> file edge
                e_key = self._safe_arango_key([current_branch_key, "contains_file", f_key], max_len=250)
                if not edges_col.has(e_key):
                    try:
                        edges_col.insert({
                            "_key": e_key,
                            "_from": f"KnowledgeNodes/{current_branch_key}",
                            "_to": f"KnowledgeNodes/{f_key}",
                            "relation": "contains_file",
                            "relationNormalized": "contains_file",
                            "evidenceChunkIds": [],
                        })
                        edge_count += 1
                    except Exception:
                        pass

            # 5. Upsert class nodes (from tree-sitter or request)
            for cls in effective_classes:
                qname = cls.get("qualifiedName") or cls["name"]
                c_key = self._make_class_key(qname, branch, project_id)
                methods = cls.get("methods", [])
                if not nodes_col.has(c_key):
                    nodes_col.insert({
                        "_key": c_key,
                        "canonicalKey": f"class:{qname}:branch:{branch}:{project_id}",
                        "label": cls["name"],
                        "type": "class",
                        "qualifiedName": qname,
                        "filePath": cls.get("filePath", ""),
                        "branchName": branch,
                        "isInterface": cls.get("isInterface", False),
                        "visibility": cls.get("visibility", "public"),
                        "methodCount": len(methods),
                        "clientId": client_id,
                        "projectId": project_id,
                        "groupId": "",
                        "ragChunks": [],
                    })
                    created += 1
                else:
                    nodes_col.update({
                        "_key": c_key,
                        "methodCount": len(methods),
                    })
                    updated += 1
                # file -> class edge
                if cls.get("filePath"):
                    f_key = self._make_file_key(cls["filePath"], branch, project_id)
                    e_key = self._safe_arango_key([f_key, "contains", c_key], max_len=250)
                    if nodes_col.has(f_key) and not edges_col.has(e_key):
                        try:
                            edges_col.insert({
                                "_key": e_key,
                                "_from": f"KnowledgeNodes/{f_key}",
                                "_to": f"KnowledgeNodes/{c_key}",
                                "relation": "contains",
                                "relationNormalized": "contains",
                                "evidenceChunkIds": [],
                            })
                            edge_count += 1
                        except Exception:
                            pass

                # 6. Upsert method nodes (from tree-sitter parsed classes)
                for method_name in methods[:50]:  # Max 50 methods per class
                    m_key = self._make_method_key(qname, method_name, branch, project_id)
                    if not nodes_col.has(m_key):
                        nodes_col.insert({
                            "_key": m_key,
                            "canonicalKey": f"method:{qname}.{method_name}:branch:{branch}:{project_id}",
                            "label": method_name,
                            "type": "method",
                            "className": qname,
                            "filePath": cls.get("filePath", ""),
                            "branchName": branch,
                            "clientId": client_id,
                            "projectId": project_id,
                            "groupId": "",
                            "ragChunks": [],
                        })
                        created += 1
                        methods_count += 1
                    else:
                        updated += 1
                    # class -> method edge (has_method)
                    e_key = self._safe_arango_key([c_key, "has_method", m_key], max_len=250)
                    if not edges_col.has(e_key):
                        try:
                            edges_col.insert({
                                "_key": e_key,
                                "_from": f"KnowledgeNodes/{c_key}",
                                "_to": f"KnowledgeNodes/{m_key}",
                                "relation": "has_method",
                                "relationNormalized": "has_method",
                                "evidenceChunkIds": [],
                            })
                            edge_count += 1
                        except Exception:
                            pass

            # 7. Create import edges (file → class, from tree-sitter imports)
            for import_file_path, import_names in file_imports.items():
                f_key = self._make_file_key(import_file_path, branch, project_id)
                if not nodes_col.has(f_key):
                    continue
                for import_name in import_names[:100]:  # Max 100 imports per file
                    # Try to match import to an existing class node
                    target_key = self._make_class_key(import_name, branch, project_id)
                    if not nodes_col.has(target_key):
                        # Try short name match (last segment)
                        short_name = import_name.rsplit(".", 1)[-1]
                        target_key = self._make_class_key(short_name, branch, project_id)
                        if not nodes_col.has(target_key):
                            continue
                    e_key = self._safe_arango_key([f_key, "imports", target_key], max_len=250)
                    if not edges_col.has(e_key):
                        try:
                            edges_col.insert({
                                "_key": e_key,
                                "_from": f"KnowledgeNodes/{f_key}",
                                "_to": f"KnowledgeNodes/{target_key}",
                                "relation": "imports",
                                "relationNormalized": "imports",
                                "evidenceChunkIds": [],
                            })
                            edge_count += 1
                        except Exception:
                            pass

            return created, updated, edge_count, methods_count

        nodes_created, nodes_updated, edges_created, methods_indexed = await asyncio.to_thread(_upsert)

        logger.info(
            "Git structure ingest: repo=%s branch=%s nodes_created=%d "
            "nodes_updated=%d edges=%d files=%d classes=%d methods=%d "
            "file_contents=%d tree_sitter=%s",
            repo_identifier, branch, nodes_created, nodes_updated,
            edges_created, len(files), len(effective_classes), methods_indexed,
            len(file_contents), bool(parsed_classes),
        )

        return {
            "nodes_created": nodes_created,
            "edges_created": edges_created,
            "nodes_updated": nodes_updated,
            "repository_key": repo_key,
            "branch_key": current_branch_key,
            "files_indexed": min(len(files), 500),
            "classes_indexed": len(effective_classes),
            "methods_indexed": methods_indexed,
        }

    # -----------------------------------------------------------------------
    # Joern CPG ingest — enriches graph with semantic edges
    # -----------------------------------------------------------------------

    async def ingest_cpg_export(
        self,
        client_id: str,
        project_id: str,
        branch: str,
        cpg_data: dict,
    ) -> dict:
        """Import pruned Joern CPG into ArangoDB.

        Enriches existing nodes (from tree-sitter) and adds semantic edges:
        - calls: method → method (call graph)
        - extends: class → class (inheritance)
        - uses_type: class → class (field/param type references)

        Also enriches method nodes with Joern-specific data (signature).

        Args:
            client_id: Client ID
            project_id: Project ID
            branch: The branch analyzed
            cpg_data: Parsed CPG export JSON with keys: methods, types, calls, typeRefs

        Returns:
            Dict with counts of edges and enrichments created.
        """
        methods_data = cpg_data.get("methods", [])
        types_data = cpg_data.get("types", [])
        calls_data = cpg_data.get("calls", [])
        type_refs_data = cpg_data.get("typeRefs", [])

        def _ingest():
            nodes_col = self.db.collection("KnowledgeNodes")
            edges_col = self.db.collection("KnowledgeEdges")
            enriched = 0
            edges_created = 0
            nodes_created = 0

            # Build lookup: Joern fullName → ArangoDB method key
            # Joern fullName format varies: "com.pkg.Class.method:returnType(paramTypes)"
            # We try matching by class_qname + method_name

            # 1. Enrich method nodes with Joern signature
            for m in methods_data:
                full_name = m.get("fullName", "")
                signature = m.get("signature", "")
                if not full_name or not signature:
                    continue

                # Parse fullName: last segment before : is method name
                # fullName could be "Namespace.Class.method:type(types)"
                name_part = full_name.split(":")[0] if ":" in full_name else full_name
                parts = name_part.rsplit(".", 1)
                if len(parts) == 2:
                    class_qname, method_name = parts
                else:
                    continue

                m_key = self._make_method_key(class_qname, method_name, branch, project_id)
                if nodes_col.has(m_key):
                    try:
                        nodes_col.update({
                            "_key": m_key,
                            "signature": signature,
                            "joernFullName": full_name,
                        })
                        enriched += 1
                    except Exception:
                        pass

            # 2. Add inheritance edges (extends) from type declarations
            for t in types_data:
                child_name = t.get("fullName", "")
                parents = t.get("inheritsFrom", [])
                if not child_name or not parents:
                    continue

                child_key = self._make_class_key(child_name, branch, project_id)
                if not nodes_col.has(child_key):
                    # Try short name
                    short = child_name.rsplit(".", 1)[-1]
                    child_key = self._make_class_key(short, branch, project_id)
                    if not nodes_col.has(child_key):
                        continue

                for parent_name in parents:
                    if not parent_name or parent_name in ("ANY", "Object", "java.lang.Object"):
                        continue
                    parent_key = self._make_class_key(parent_name, branch, project_id)
                    if not nodes_col.has(parent_key):
                        short_parent = parent_name.rsplit(".", 1)[-1]
                        parent_key = self._make_class_key(short_parent, branch, project_id)
                        if not nodes_col.has(parent_key):
                            continue

                    e_key = self._safe_arango_key([child_key, "extends", parent_key], max_len=250)
                    if not edges_col.has(e_key):
                        try:
                            edges_col.insert({
                                "_key": e_key,
                                "_from": f"KnowledgeNodes/{child_key}",
                                "_to": f"KnowledgeNodes/{parent_key}",
                                "relation": "extends",
                                "relationNormalized": "extends",
                                "evidenceChunkIds": [],
                            })
                            edges_created += 1
                        except Exception:
                            pass

            # 3. Add call edges (calls) from Joern call analysis
            for c in calls_data:
                caller_full = c.get("callerMethod", "")
                callee_full = c.get("calledMethod", "")
                if not caller_full or not callee_full:
                    continue

                # Parse caller and callee into class.method
                def _parse_method_ref(full_name: str):
                    name_part = full_name.split(":")[0] if ":" in full_name else full_name
                    parts = name_part.rsplit(".", 1)
                    return tuple(parts) if len(parts) == 2 else (None, None)

                caller_cls, caller_method = _parse_method_ref(caller_full)
                callee_cls, callee_method = _parse_method_ref(callee_full)
                if not caller_cls or not callee_cls:
                    continue

                caller_key = self._make_method_key(caller_cls, caller_method, branch, project_id)
                callee_key = self._make_method_key(callee_cls, callee_method, branch, project_id)

                if not nodes_col.has(caller_key) or not nodes_col.has(callee_key):
                    continue

                e_key = self._safe_arango_key([caller_key, "calls", callee_key], max_len=250)
                if not edges_col.has(e_key):
                    try:
                        edges_col.insert({
                            "_key": e_key,
                            "_from": f"KnowledgeNodes/{caller_key}",
                            "_to": f"KnowledgeNodes/{callee_key}",
                            "relation": "calls",
                            "relationNormalized": "calls",
                            "evidenceChunkIds": [],
                        })
                        edges_created += 1
                    except Exception:
                        pass

            # 4. Add type reference edges (uses_type)
            for ref in type_refs_data:
                class_name = ref.get("className", "")
                member_type = ref.get("memberType", "")
                if not class_name or not member_type:
                    continue
                # Skip primitive types and common library types
                if member_type in ("int", "long", "float", "double", "boolean", "void",
                                   "String", "java.lang.String", "Any", "Unit",
                                   "List", "Map", "Set", "Optional"):
                    continue

                owner_key = self._make_class_key(class_name, branch, project_id)
                if not nodes_col.has(owner_key):
                    short_owner = class_name.rsplit(".", 1)[-1]
                    owner_key = self._make_class_key(short_owner, branch, project_id)
                    if not nodes_col.has(owner_key):
                        continue

                ref_key = self._make_class_key(member_type, branch, project_id)
                if not nodes_col.has(ref_key):
                    short_ref = member_type.rsplit(".", 1)[-1]
                    ref_key = self._make_class_key(short_ref, branch, project_id)
                    if not nodes_col.has(ref_key):
                        continue

                # Don't create self-references
                if owner_key == ref_key:
                    continue

                e_key = self._safe_arango_key([owner_key, "uses_type", ref_key], max_len=250)
                if not edges_col.has(e_key):
                    try:
                        edges_col.insert({
                            "_key": e_key,
                            "_from": f"KnowledgeNodes/{owner_key}",
                            "_to": f"KnowledgeNodes/{ref_key}",
                            "relation": "uses_type",
                            "relationNormalized": "uses_type",
                            "evidenceChunkIds": [],
                        })
                        edges_created += 1
                    except Exception:
                        pass

            return enriched, edges_created, nodes_created

        enriched, edges_created, nodes_created = await asyncio.to_thread(_ingest)

        logger.info(
            "CPG ingest: branch=%s enriched=%d edges=%d nodes=%d "
            "(methods=%d types=%d calls=%d typeRefs=%d)",
            branch, enriched, edges_created, nodes_created,
            len(methods_data), len(types_data), len(calls_data), len(type_refs_data),
        )

        return {
            "methods_enriched": enriched,
            "edges_created": edges_created,
            "nodes_created": nodes_created,
            "cpg_methods": len(methods_data),
            "cpg_types": len(types_data),
            "cpg_calls": len(calls_data),
            "cpg_type_refs": len(type_refs_data),
        }
