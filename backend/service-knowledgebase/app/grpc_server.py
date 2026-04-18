"""gRPC server for `service-knowledgebase`.

Hosts KB RPC services as they migrate off FastAPI. Live surface
evolves with each Phase 2 slice. See
`docs/inter-service-contracts-bigbang.md` §3 — Phase 2 for the
migration order.

The FastAPI surface on :8080 stays live for still-unmigrated routes
(blob multipart, legacy callers). The gRPC surface is on :5501 and
mirrors what other pods do for pod-to-pod contracts.
"""

from __future__ import annotations

import logging

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.knowledgebase import graph_pb2, graph_pb2_grpc
from jervis.knowledgebase import ingest_pb2, ingest_pb2_grpc
from jervis.knowledgebase import maintenance_pb2, maintenance_pb2_grpc
from jervis.knowledgebase import queue_pb2, queue_pb2_grpc
from jervis.knowledgebase import retrieve_pb2, retrieve_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("kb.grpc")


class MaintenanceServicer(maintenance_pb2_grpc.KnowledgeMaintenanceServiceServicer):
    """KnowledgeMaintenanceService implementation.

    RunBatch dispatches to the graph/rag/thought service batch helpers;
    Retag{Project,Group} reuse the existing KnowledgeService retag paths.
    """

    def _service(self):
        # Late import — the global service reference is filled by the
        # FastAPI lifespan hook before gRPC starts accepting traffic.
        from app.api import routes as api_routes

        if api_routes.service is None:
            raise RuntimeError("KnowledgeService not initialized")
        return api_routes.service

    async def RunBatch(
        self,
        request: maintenance_pb2.MaintenanceBatchRequest,
        context: grpc.aio.ServicerContext,
    ) -> maintenance_pb2.MaintenanceBatchResult:
        mtype = (request.maintenance_type or "").strip()
        client_id = request.client_id or ""
        cursor = request.cursor or None
        batch_size = request.batch_size or 100

        if not mtype or not client_id:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                "maintenance_type and client_id required",
            )

        service = self._service()
        try:
            if mtype == "dedup":
                raw = await service.graph_service.maintenance_dedup_batch(client_id, cursor, batch_size)
            elif mtype == "orphan_cleanup":
                raw = await service.graph_service.maintenance_orphan_batch(client_id, cursor, batch_size)
            elif mtype == "consistency_check":
                raw = await service.graph_service.maintenance_consistency_batch(client_id, cursor, batch_size)
            elif mtype == "thought_decay":
                raw = await service.thought_service.maintenance_decay_batch(client_id, cursor, batch_size)
            elif mtype == "thought_merge":
                raw = await service.thought_service.maintenance_merge_batch(client_id, cursor, batch_size)
            elif mtype == "embedding_quality":
                raw = await service.rag_service.maintenance_embedding_batch(client_id, cursor, batch_size)
            else:
                await context.abort(
                    grpc.StatusCode.INVALID_ARGUMENT,
                    f"Unknown maintenance type: {mtype}",
                )
        except Exception as e:
            logger.error("RUN_BATCH_ERROR type=%s client=%s error=%s", mtype, client_id, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))

        return maintenance_pb2.MaintenanceBatchResult(
            completed=bool(raw.get("completed", False)),
            next_cursor=str(raw.get("nextCursor") or ""),
            processed=int(raw.get("processed", 0)),
            findings=int(raw.get("findings", 0)),
            fixed=int(raw.get("fixed", 0)),
            total_estimate=int(raw.get("totalEstimate", 0)),
        )

    async def RetagProject(
        self,
        request: maintenance_pb2.RetagProjectRequest,
        context: grpc.aio.ServicerContext,
    ) -> maintenance_pb2.RetagResult:
        source = request.source_project_id
        target = request.target_project_id
        if not source or not target:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                "source_project_id and target_project_id are required",
            )

        service = self._service()
        try:
            graph_results = await service.graph_service.retag_project(source, target)
            weaviate_updated = await service.rag_service.retag_project(source, target)
        except Exception as e:
            logger.error("RETAG_PROJECT_ERROR source=%s target=%s error=%s", source, target, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))

        # graph_results shape: {"chunks": N, "nodes": M, ...} — sum totals for
        # a single scalar; detail stays wire-visible via logs.
        graph_total = sum(int(v) for v in (graph_results or {}).values() if isinstance(v, (int, float)))
        return maintenance_pb2.RetagResult(
            status="success",
            graph_updated=graph_total,
            weaviate_updated=int(weaviate_updated or 0),
        )

    async def RetagGroup(
        self,
        request: maintenance_pb2.RetagGroupRequest,
        context: grpc.aio.ServicerContext,
    ) -> maintenance_pb2.RetagResult:
        project_id = request.project_id
        new_group_id = request.new_group_id or None
        if not project_id:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "project_id is required")

        service = self._service()
        try:
            graph_updated = await service.graph_service.retag_group(project_id, new_group_id)
            weaviate_updated = await service.rag_service.retag_group(project_id, new_group_id)
        except Exception as e:
            logger.error("RETAG_GROUP_ERROR project=%s group=%s error=%s", project_id, new_group_id, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))

        return maintenance_pb2.RetagResult(
            status="success",
            graph_updated=int(graph_updated or 0),
            weaviate_updated=int(weaviate_updated or 0),
        )


class QueueServicer(queue_pb2_grpc.KnowledgeQueueServiceServicer):
    """KnowledgeQueueService implementation — LLM extraction queue listing.

    The queue is only present on write-mode pods (read-mode pods skip the
    SQLite-backed LLMExtractionQueue). For read-mode requests we return an
    empty list + zeroed stats so UI polling does not fail cross-mode.
    """

    async def ListQueue(
        self,
        request: queue_pb2.QueueListRequest,
        context: grpc.aio.ServicerContext,
    ) -> queue_pb2.QueueList:
        from app.api import routes as api_routes

        if api_routes.service is None:
            return queue_pb2.QueueList(items=[], stats=queue_pb2.QueueStats())

        queue = api_routes.service.extraction_queue
        if queue is None:
            return queue_pb2.QueueList(items=[], stats=queue_pb2.QueueStats())

        limit = request.limit or 200
        raw_items = await queue.list_queue(limit=limit)
        raw_stats = await queue.stats()

        items = [
            queue_pb2.QueueItem(
                task_id=str(it.get("task_id", "")),
                source_urn=str(it.get("source_urn", "")),
                client_id=str(it.get("client_id", "")),
                project_id=str(it.get("project_id") or ""),
                kind=str(it.get("kind") or ""),
                created_at=str(it.get("created_at") or ""),
                status=str(it.get("status") or ""),
                attempts=int(it.get("attempts", 0)),
                priority=int(it.get("priority", 4)),
                error=str(it.get("error") or ""),
                last_attempt_at=str(it.get("last_attempt_at") or ""),
                worker_id=str(it.get("worker_id") or ""),
                progress_current=int(it.get("progress_current", 0)),
                progress_total=int(it.get("progress_total", 0)),
            )
            for it in (raw_items or [])
        ]
        stats = queue_pb2.QueueStats(
            total=int(raw_stats.get("total", 0)),
            pending=int(raw_stats.get("pending", 0)),
            in_progress=int(raw_stats.get("in_progress", 0)),
            failed=int(raw_stats.get("failed", 0)),
        )
        return queue_pb2.QueueList(items=items, stats=stats)


class IngestServicer(ingest_pb2_grpc.KnowledgeIngestServiceServicer):
    """KnowledgeIngestService — Kotlin-only surfaces land first.

    RPCs not yet migrated (Ingest, IngestImmediate, IngestFull, IngestFile,
    Crawl, Purge, …) stay on FastAPI and return UNIMPLEMENTED here — the
    Python clients that still call those endpoints dial the REST port
    until the matching slice lands.
    """

    def _service(self):
        from app.api import routes as api_routes

        if api_routes.service is None:
            raise RuntimeError("KnowledgeService not initialized")
        return api_routes.service

    async def IngestCpg(
        self,
        request: ingest_pb2.CpgIngestRequest,
        context: grpc.aio.ServicerContext,
    ) -> ingest_pb2.CpgIngestResult:
        from app.api.models import CpgIngestRequest as CpgReq

        req = CpgReq(
            clientId=request.client_id,
            projectId=request.project_id,
            branch=request.branch,
            workspacePath=request.workspace_path,
        )
        try:
            result = await self._service().ingest_cpg(req)
        except Exception as e:
            logger.error("INGEST_CPG_ERROR project=%s branch=%s error=%s",
                         request.project_id, request.branch, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))
        return ingest_pb2.CpgIngestResult(
            status=getattr(result, "status", "error"),
            methods_enriched=int(getattr(result, "methods_enriched", 0)),
            extends_edges=int(getattr(result, "extends_edges", 0)),
            calls_edges=int(getattr(result, "calls_edges", 0)),
            uses_type_edges=int(getattr(result, "uses_type_edges", 0)),
        )

    async def IngestGitStructure(
        self,
        request: ingest_pb2.GitStructureIngestRequest,
        context: grpc.aio.ServicerContext,
    ) -> ingest_pb2.GitStructureIngestResult:
        from app.api.models import (
            GitBranchInfo, GitClassInfo, GitFileContent, GitFileInfo,
            GitStructureIngestRequest as GSIR,
        )

        req = GSIR(
            clientId=request.client_id,
            projectId=request.project_id,
            repositoryIdentifier=request.repository_identifier,
            branch=request.branch,
            defaultBranch=request.default_branch,
            branches=[
                GitBranchInfo(
                    name=b.name,
                    isDefault=b.is_default,
                    status=b.status,
                    lastCommitHash=b.last_commit_hash,
                )
                for b in request.branches
            ],
            files=[
                GitFileInfo(
                    path=f.path,
                    extension=f.extension,
                    language=f.language,
                    sizeBytes=f.size_bytes,
                )
                for f in request.files
            ],
            classes=[
                GitClassInfo(
                    name=c.name,
                    qualifiedName=c.qualified_name,
                    filePath=c.file_path,
                    visibility=c.visibility,
                    isInterface=c.is_interface,
                    methods=list(c.methods),
                )
                for c in request.classes
            ],
            fileContents=[
                GitFileContent(path=fc.path, content=fc.content)
                for fc in request.file_contents
            ],
            metadata=dict(request.metadata),
        )
        try:
            result = await self._service().ingest_git_structure(req)
        except Exception as e:
            logger.error("INGEST_GIT_STRUCTURE_ERROR project=%s branch=%s error=%s",
                         request.project_id, request.branch, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))
        return ingest_pb2.GitStructureIngestResult(
            status=getattr(result, "status", "error"),
            nodes_created=int(getattr(result, "nodesCreated", getattr(result, "nodes_created", 0))),
            edges_created=int(getattr(result, "edgesCreated", getattr(result, "edges_created", 0))),
            nodes_updated=int(getattr(result, "nodesUpdated", getattr(result, "nodes_updated", 0))),
            repository_key=str(getattr(result, "repositoryKey", getattr(result, "repository_key", "")) or ""),
            branch_key=str(getattr(result, "branchKey", getattr(result, "branch_key", "")) or ""),
            files_indexed=int(getattr(result, "filesIndexed", getattr(result, "files_indexed", 0))),
            classes_indexed=int(getattr(result, "classesIndexed", getattr(result, "classes_indexed", 0))),
            methods_indexed=int(getattr(result, "methodsIndexed", getattr(result, "methods_indexed", 0))),
        )

    def _ingest_to_pydantic(self, request: ingest_pb2.IngestRequest):
        from app.api.models import IngestRequest as IReq, SourceCredibility

        cred = None
        if request.credibility:
            try:
                cred = SourceCredibility(request.credibility)
            except ValueError:
                cred = None
        return IReq(
            clientId=request.client_id,
            projectId=request.project_id or None,
            groupId=request.group_id or None,
            sourceUrn=request.source_urn,
            kind=request.kind or "note",
            content=request.content,
            metadata=dict(request.metadata),
            observedAt=request.observed_at_iso or None,
            maxTier=request.max_tier or "NONE",
            credibility=cred,
            branchScope=request.branch_scope or None,
            branchRole=request.branch_role or None,
        )

    def _ingest_result_to_proto(self, result) -> ingest_pb2.IngestResult:
        status = getattr(result, "status", None) or "success"
        chunks = int(getattr(result, "chunks_count", None) or getattr(result, "chunksCount", 0) or 0)
        nodes = int(getattr(result, "nodes_created", None) or getattr(result, "nodesCreated", 0) or 0)
        edges = int(getattr(result, "edges_created", None) or getattr(result, "edgesCreated", 0) or 0)
        chunk_ids = list(
            getattr(result, "chunk_ids", None)
            or getattr(result, "chunkIds", None)
            or []
        )
        entity_keys = list(
            getattr(result, "entity_keys", None)
            or getattr(result, "entityKeys", None)
            or []
        )
        return ingest_pb2.IngestResult(
            status=str(status),
            chunks_count=chunks,
            nodes_created=nodes,
            edges_created=edges,
            chunk_ids=chunk_ids,
            entity_keys=entity_keys,
        )

    async def Ingest(
        self,
        request: ingest_pb2.IngestRequest,
        context: grpc.aio.ServicerContext,
    ) -> ingest_pb2.IngestResult:
        try:
            result = await self._service().ingest(self._ingest_to_pydantic(request))
        except Exception as e:
            logger.warning("INGEST_ERROR source=%s error=%s", request.source_urn, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))
        return self._ingest_result_to_proto(result)

    async def IngestImmediate(
        self,
        request: ingest_pb2.IngestRequest,
        context: grpc.aio.ServicerContext,
    ) -> ingest_pb2.IngestResult:
        try:
            result = await self._service().ingest_immediate(self._ingest_to_pydantic(request))
        except Exception as e:
            logger.warning("INGEST_IMMEDIATE_ERROR source=%s error=%s", request.source_urn, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))
        return self._ingest_result_to_proto(result)

    async def IngestQueue(
        self,
        request: ingest_pb2.IngestRequest,
        context: grpc.aio.ServicerContext,
    ) -> ingest_pb2.IngestQueueAck:
        import asyncio

        try:
            asyncio.create_task(self._service().ingest(self._ingest_to_pydantic(request)))
        except Exception as e:
            logger.warning("INGEST_QUEUE_ERROR source=%s error=%s", request.source_urn, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))
        return ingest_pb2.IngestQueueAck(ok=True, queue_id=request.source_urn or "")

    async def Purge(
        self,
        request: ingest_pb2.PurgeRequest,
        context: grpc.aio.ServicerContext,
    ) -> ingest_pb2.PurgeResult:
        source_urn = request.source_urn
        if not source_urn:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "source_urn is required")
        try:
            result = await self._service().purge(source_urn)
        except Exception as e:
            logger.error("PURGE_ERROR sourceUrn=%s error=%s", source_urn, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))

        # KnowledgeService.purge returns {"chunks_deleted", "nodes_cleaned", ...}
        return ingest_pb2.PurgeResult(
            status="success",
            chunks_deleted=int(result.get("chunks_deleted", 0)),
            nodes_cleaned=int(result.get("nodes_cleaned", 0)),
            edges_cleaned=int(result.get("edges_cleaned", 0)),
            nodes_deleted=int(result.get("nodes_deleted", 0)),
            edges_deleted=int(result.get("edges_deleted", 0)),
        )

    async def IngestGitCommits(
        self,
        request: ingest_pb2.GitCommitIngestRequest,
        context: grpc.aio.ServicerContext,
    ) -> ingest_pb2.GitCommitIngestResult:
        from app.api.models import GitCommitIngestRequest as GCIR, GitCommitInfo

        req = GCIR(
            clientId=request.client_id,
            projectId=request.project_id,
            repositoryIdentifier=request.repository_identifier,
            branch=request.branch,
            commits=[
                GitCommitInfo(
                    hash=c.hash,
                    message=c.message,
                    author=c.author,
                    date=c.date,
                    branch=c.branch,
                    parentHash=c.parent_hash,
                    filesModified=list(c.files_modified),
                    filesCreated=list(c.files_created),
                    filesDeleted=list(c.files_deleted),
                )
                for c in request.commits
            ],
            diffContent=request.diff_content or None,
        )
        try:
            result = await self._service().ingest_git_commits(req)
        except Exception as e:
            logger.error("INGEST_GIT_COMMITS_ERROR project=%s branch=%s commits=%d error=%s",
                         request.project_id, request.branch, len(request.commits), e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))
        return ingest_pb2.GitCommitIngestResult(
            status=getattr(result, "status", "error"),
            commits_ingested=int(getattr(result, "commitsIngested", getattr(result, "commits_ingested", 0))),
            nodes_created=int(getattr(result, "nodesCreated", getattr(result, "nodes_created", 0))),
            edges_created=int(getattr(result, "edgesCreated", getattr(result, "edges_created", 0))),
            rag_chunks=int(getattr(result, "ragChunks", getattr(result, "rag_chunks", 0))),
        )


class RetrieveServicer(retrieve_pb2_grpc.KnowledgeRetrieveServiceServicer):
    """KnowledgeRetrieveService — read-side RAG + graph retrieval.

    Retrieve (hybrid) and RetrieveSimple (RAG-only) land in this slice.
    RetrieveHybrid with full knobs, AnalyzeCode, JoernScan, and
    ListChunksByKind stay on FastAPI until their slices land.
    """

    def _service(self):
        from app.api import routes as api_routes

        if api_routes.service is None:
            raise RuntimeError("KnowledgeService not initialized")
        return api_routes.service

    def _to_pydantic(self, request: retrieve_pb2.RetrievalRequest):
        from app.api.models import RetrievalRequest as RReq

        return RReq(
            query=request.query,
            clientId=request.client_id or "",
            projectId=request.project_id or None,
            groupId=request.group_id or None,
            maxResults=request.max_results or 5,
            minConfidence=request.min_confidence or 0.0,
            expandGraph=request.expand_graph,
        )

    def _evidence_to_proto(self, pack) -> retrieve_pb2.EvidencePack:
        items = []
        for it in getattr(pack, "items", []) or []:
            meta_str: dict[str, str] = {}
            for k, v in (getattr(it, "metadata", None) or {}).items():
                meta_str[str(k)] = "" if v is None else str(v)
            items.append(
                retrieve_pb2.EvidenceItem(
                    content=str(getattr(it, "content", "") or ""),
                    score=float(getattr(it, "score", 0.0) or 0.0),
                    source_urn=str(getattr(it, "sourceUrn", "") or ""),
                    credibility=str(getattr(it, "credibility", "") or ""),
                    branch_scope=str(getattr(it, "branchScope", "") or ""),
                    metadata=meta_str,
                )
            )
        return retrieve_pb2.EvidencePack(items=items)

    async def Retrieve(
        self,
        request: retrieve_pb2.RetrievalRequest,
        context: grpc.aio.ServicerContext,
    ) -> retrieve_pb2.EvidencePack:
        try:
            pack = await self._service().retrieve(self._to_pydantic(request))
        except Exception as e:
            logger.warning("RETRIEVE_ERROR query=%r error=%s", request.query[:120], e)
            return retrieve_pb2.EvidencePack(items=[])
        return self._evidence_to_proto(pack)

    async def RetrieveSimple(
        self,
        request: retrieve_pb2.RetrievalRequest,
        context: grpc.aio.ServicerContext,
    ) -> retrieve_pb2.EvidencePack:
        try:
            pack = await self._service().retrieve_simple(self._to_pydantic(request))
        except Exception as e:
            logger.warning("RETRIEVE_SIMPLE_ERROR query=%r error=%s", request.query[:120], e)
            return retrieve_pb2.EvidencePack(items=[])
        return self._evidence_to_proto(pack)


class GraphServicer(graph_pb2_grpc.KnowledgeGraphServiceServicer):
    """KnowledgeGraphService — graph traversal, node lookup, alias registry.

    Thought Map RPCs still live on FastAPI until their slice migrates.
    """

    def _service(self):
        from app.api import routes as api_routes

        if api_routes.service is None:
            raise RuntimeError("KnowledgeService not initialized")
        return api_routes.service

    def _node_to_proto(self, node) -> graph_pb2.GraphNode:
        """Project a Pydantic/dict GraphNode to proto. Properties are stringified
        to fit map<string,string> — matches the legacy REST JSON shape which
        kotlinx-serialization already forced to strings on read."""
        key = getattr(node, "key", None) or (node.get("key") if isinstance(node, dict) else "") or ""
        label = getattr(node, "label", None) or (node.get("label") if isinstance(node, dict) else "") or ""
        node_id = getattr(node, "id", None) or (node.get("id") if isinstance(node, dict) else "") or key
        raw_props = getattr(node, "properties", None)
        if raw_props is None and isinstance(node, dict):
            raw_props = node.get("properties") or {}
        props: dict[str, str] = {}
        for k, v in (raw_props or {}).items():
            if v is None:
                props[str(k)] = ""
            elif isinstance(v, (dict, list)):
                import json as _json
                props[str(k)] = _json.dumps(v, ensure_ascii=False)
            else:
                props[str(k)] = str(v)
        return graph_pb2.GraphNode(id=str(node_id), key=str(key), label=str(label), properties=props)

    async def Traverse(
        self,
        request: graph_pb2.TraversalRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.GraphNodeList:
        from app.api.models import TraversalRequest as TReq, TraversalSpec as TSpec

        spec_msg = request.spec
        spec = TSpec(
            direction=spec_msg.direction or "OUTBOUND",
            minDepth=spec_msg.min_depth or 1,
            maxDepth=spec_msg.max_depth or 1,
            edgeCollection=spec_msg.edge_collection or None,
        )
        req = TReq(
            clientId=request.client_id or "",
            startKey=request.start_key,
            spec=spec,
        )
        try:
            nodes = await self._service().traverse(req)
        except Exception as e:
            logger.warning("TRAVERSE_ERROR start=%s error=%s", request.start_key, e)
            return graph_pb2.GraphNodeList(nodes=[])
        return graph_pb2.GraphNodeList(nodes=[self._node_to_proto(n) for n in (nodes or [])])

    async def GetNode(
        self,
        request: graph_pb2.GetNodeRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.GraphNode:
        graph_service = self._service().graph_service
        try:
            node = await graph_service.get_node(
                request.node_key,
                request.client_id or "",
                request.project_id or None,
                request.group_id or None,
            )
        except Exception as e:
            logger.warning("GET_NODE_ERROR key=%s error=%s", request.node_key, e)
            return graph_pb2.GraphNode()
        if node is None:
            return graph_pb2.GraphNode()
        return self._node_to_proto(node)

    async def SearchNodes(
        self,
        request: graph_pb2.SearchNodesRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.GraphNodeList:
        graph_service = self._service().graph_service
        try:
            nodes = await graph_service.search_nodes(
                query=request.query or "",
                client_id=request.client_id or "",
                project_id=request.project_id or None,
                group_id=request.group_id or None,
                node_type=request.node_type or None,
                branch_name=request.branch_name or None,
                limit=request.max_results or 20,
            )
        except Exception as e:
            logger.warning("SEARCH_NODES_ERROR query=%r error=%s", (request.query or "")[:120], e)
            return graph_pb2.GraphNodeList(nodes=[])
        return graph_pb2.GraphNodeList(nodes=[self._node_to_proto(n) for n in (nodes or [])])

    async def GetNodeEvidence(
        self,
        request: graph_pb2.GetNodeRequest,
        context: grpc.aio.ServicerContext,
    ) -> retrieve_pb2.EvidencePack:
        graph_service = self._service().graph_service
        try:
            chunk_ids = await graph_service.get_node_chunks(request.node_key, request.client_id or "")
            if not chunk_ids:
                return retrieve_pb2.EvidencePack(items=[])
            chunks = await self._service().rag_service.get_chunks_by_ids(chunk_ids)
        except Exception as e:
            logger.warning("GET_NODE_EVIDENCE_ERROR key=%s error=%s", request.node_key, e)
            return retrieve_pb2.EvidencePack(items=[])

        items: list[retrieve_pb2.EvidenceItem] = []
        for c in (chunks or []):
            meta: dict[str, str] = {}
            for k, v in ((c.get("metadata") if isinstance(c, dict) else None) or {}).items():
                meta[str(k)] = "" if v is None else str(v)
            items.append(
                retrieve_pb2.EvidenceItem(
                    content=str((c.get("content") if isinstance(c, dict) else "") or ""),
                    score=float((c.get("score") if isinstance(c, dict) else 0.0) or 0.0),
                    source_urn=str((c.get("sourceUrn") or c.get("source_urn") if isinstance(c, dict) else "") or ""),
                    credibility="",
                    branch_scope="",
                    metadata=meta,
                )
            )
        return retrieve_pb2.EvidencePack(items=items)

    async def ResolveAlias(
        self,
        request: graph_pb2.ResolveAliasRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.AliasResolveResult:
        registry = self._service().graph_service.alias_registry
        try:
            canonical = await registry.resolve(request.client_id, request.alias)
        except Exception as e:
            logger.error("ALIAS_RESOLVE_ERROR alias=%s error=%s", request.alias, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))
        # resolve() returns the normalized alias when no entry exists; the
        # REST handler expressed this as canonical=<normalized>. Keep that
        # shape, with `found` telling the caller whether an entry existed.
        return graph_pb2.AliasResolveResult(
            found=bool(canonical and canonical != request.alias),
            canonical_key=str(canonical or ""),
            canonical_label="",
        )

    async def ListAliases(
        self,
        request: graph_pb2.ListAliasesRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.AliasList:
        registry = self._service().graph_service.alias_registry
        try:
            aliases = await registry.get_aliases("", request.canonical_key)
        except Exception as e:
            logger.error("ALIAS_LIST_ERROR canonical=%s error=%s", request.canonical_key, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))
        return graph_pb2.AliasList(aliases=list(aliases or []))

    async def GetAliasStats(
        self,
        request: graph_pb2.AliasStatsRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.AliasStats:
        registry = self._service().graph_service.alias_registry
        try:
            stats = await registry.get_stats(request.client_id)
        except Exception as e:
            logger.error("ALIAS_STATS_ERROR client=%s error=%s", request.client_id, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))

        return graph_pb2.AliasStats(
            total_aliases=int(stats.get("totalAliases", 0) or 0),
            unique_canonicals=int(stats.get("uniqueCanonicals", 0) or 0),
            top_aliases=[
                graph_pb2.AliasTopItem(
                    alias=str(t.get("alias", "") or ""),
                    canonical=str(t.get("canonical", "") or ""),
                    count=int(t.get("count", 0) or 0),
                )
                for t in (stats.get("topAliases") or [])
            ],
        )

    async def RegisterAlias(
        self,
        request: graph_pb2.RegisterAliasRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.AliasAck:
        registry = self._service().graph_service.alias_registry
        try:
            await registry.register(
                request.client_id,
                request.alias,
                request.canonical_key or None,
            )
        except Exception as e:
            logger.error("ALIAS_REGISTER_ERROR alias=%s error=%s", request.alias, e)
            return graph_pb2.AliasAck(ok=False, error=str(e))
        return graph_pb2.AliasAck(ok=True, error="")

    async def MergeAlias(
        self,
        request: graph_pb2.MergeAliasRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.AliasAck:
        registry = self._service().graph_service.alias_registry
        try:
            await registry.merge(request.client_id, request.from_key, request.into_key)
        except Exception as e:
            logger.error("ALIAS_MERGE_ERROR from=%s into=%s error=%s",
                         request.from_key, request.into_key, e)
            return graph_pb2.AliasAck(ok=False, error=str(e))
        return graph_pb2.AliasAck(ok=True, error="")

    # ── Thought Map ────────────────────────────────────────────────────

    def _thought_service(self):
        from app.api import routes as api_routes

        if api_routes.service is None:
            raise RuntimeError("KnowledgeService not initialized")
        # thought_service is attached to graph_service, matching the FastAPI wiring.
        return api_routes.service.thought_service

    def _thought_to_entry(self, t) -> graph_pb2.ThoughtEntry:
        """Map a ThoughtService.traverse item to proto.

        ThoughtService.traverse returns items of shape {
            node: {_id/label/type/summary/description/activationScore/...},
            pathWeight, isEntryPoint,
        }. We flatten that into ThoughtEntry so consumers don't have to
        reach through the `node` wrapper on the wire.
        """
        if not isinstance(t, dict):
            t = {}
        node = t.get("node") if isinstance(t.get("node"), dict) else t
        meta: dict[str, str] = {}
        for k, v in (node.get("metadata") or {}).items():
            if v is None:
                meta[str(k)] = ""
            elif isinstance(v, (dict, list)):
                import json as _json
                meta[str(k)] = _json.dumps(v, ensure_ascii=False)
            else:
                meta[str(k)] = str(v)
        return graph_pb2.ThoughtEntry(
            id=str(node.get("_id") or node.get("id") or node.get("_key") or node.get("key") or ""),
            label=str(node.get("label") or ""),
            summary=str(node.get("summary") or ""),
            node_type=str(node.get("type") or node.get("node_type") or ""),
            activation=float(node.get("activationScore") or node.get("activation") or 0.0),
            path_weight=float(t.get("pathWeight") or 0.0),
            is_entry_point=bool(t.get("isEntryPoint") or False),
            description=str(node.get("description") or ""),
            metadata=meta,
        )

    async def ThoughtTraverse(
        self,
        request: graph_pb2.ThoughtTraversalRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.ThoughtTraversalResult:
        svc = self._thought_service()
        try:
            result = await svc.traverse(
                query=request.query,
                client_id=request.client_id or "",
                project_id=request.project_id or "",
                group_id=request.group_id or "",
                max_results=request.max_results or 20,
                floor=request.floor or 0.0,
                max_depth=request.max_depth or 2,
                entry_top_k=request.entry_top_k or 5,
            )
        except Exception as e:
            logger.warning("THOUGHT_TRAVERSE_ERROR query=%r error=%s", request.query[:120], e)
            return graph_pb2.ThoughtTraversalResult()

        thoughts = [self._thought_to_entry(t) for t in (result.get("thoughts") or [])]
        knowledge = [self._thought_to_entry(k) for k in (result.get("knowledge") or [])]
        return graph_pb2.ThoughtTraversalResult(
            thoughts=thoughts,
            knowledge=knowledge,
            activated_thought_ids=list(result.get("activated_thought_ids") or []),
            activated_edge_ids=list(result.get("activated_edge_ids") or []),
        )

    async def ThoughtReinforce(
        self,
        request: graph_pb2.ThoughtReinforceRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.ThoughtAck:
        svc = self._thought_service()
        try:
            await svc.reinforce(
                thought_keys=list(request.thought_keys),
                edge_keys=list(request.edge_keys),
            )
        except Exception as e:
            logger.warning("THOUGHT_REINFORCE_ERROR error=%s", e)
            return graph_pb2.ThoughtAck(ok=False, detail=str(e))
        return graph_pb2.ThoughtAck(ok=True, detail="")

    async def ThoughtCreate(
        self,
        request: graph_pb2.ThoughtCreateRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.ThoughtAck:
        svc = self._thought_service()
        created_keys: list[str] = []
        try:
            for seed in request.thoughts:
                key = await svc.upsert_thought(
                    label=seed.label,
                    summary=seed.summary,
                    thought_type=seed.thought_type or "topic",
                    client_id=request.client_id or "",
                    project_id=request.project_id or "",
                    group_id=request.group_id or "",
                    related_entity_keys=list(seed.related_entities),
                    embedding_priority=2,
                )
                if key:
                    created_keys.append(key)
        except Exception as e:
            logger.warning("THOUGHT_CREATE_ERROR error=%s", e)
            return graph_pb2.ThoughtAck(ok=False, detail=str(e))
        return graph_pb2.ThoughtAck(ok=True, detail=",".join(created_keys))

    async def ThoughtBootstrap(
        self,
        request: graph_pb2.ThoughtBootstrapRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.ThoughtAck:
        from app.services.llm_router import llm_generate
        from app.core.config import settings as _settings

        async def _bootstrap_llm(prompt: str, priority: int = 2) -> str:
            return await llm_generate(
                prompt=prompt,
                model=_settings.LLM_MODEL,
                num_ctx=8192,
                priority=priority,
                temperature=0,
                format_json=False,
            )

        svc = self._thought_service()
        try:
            result = await svc.bootstrap(
                client_id=request.client_id or "",
                project_id=request.project_id or "",
                group_id=request.group_id or "",
                llm_call_fn=_bootstrap_llm,
            )
        except Exception as e:
            logger.warning("THOUGHT_BOOTSTRAP_ERROR error=%s", e)
            return graph_pb2.ThoughtAck(ok=False, detail=str(e))
        detail = "" if not isinstance(result, dict) else str(result.get("status") or "ok")
        return graph_pb2.ThoughtAck(ok=True, detail=detail)

    async def ThoughtMaintain(
        self,
        request: graph_pb2.ThoughtMaintenanceRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.ThoughtAck:
        svc = self._thought_service()
        try:
            if (request.mode or "").lower() == "heavy":
                from app.services.thought_maintenance import run_heavy_maintenance
                result = await run_heavy_maintenance(svc, request.client_id or "")
            else:
                from app.services.thought_maintenance import run_light_maintenance
                result = await run_light_maintenance(svc, request.client_id or "")
        except Exception as e:
            logger.warning("THOUGHT_MAINTAIN_ERROR mode=%s error=%s", request.mode, e)
            return graph_pb2.ThoughtAck(ok=False, detail=str(e))
        detail = "" if not isinstance(result, dict) else str(result.get("status") or "ok")
        return graph_pb2.ThoughtAck(ok=True, detail=detail)

    async def ThoughtStats(
        self,
        request: graph_pb2.ThoughtStatsRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.ThoughtStatsResult:
        svc = self._thought_service()
        try:
            stats = await svc.get_stats(request.client_id or "")
        except Exception as e:
            logger.warning("THOUGHT_STATS_ERROR error=%s", e)
            return graph_pb2.ThoughtStatsResult()

        return graph_pb2.ThoughtStatsResult(
            total_thoughts=int(stats.get("totalThoughts", stats.get("nodes", 0)) or 0),
            active_thoughts=int(stats.get("activeThoughts", stats.get("anchors", 0)) or 0),
            total_edges=int(stats.get("totalEdges", stats.get("edges", 0)) or 0),
            avg_activation=float(stats.get("avgActivation", 0.0) or 0.0),
        )


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    """Start the gRPC server on `port` and return the handle for later cleanup.

    The server registers every KB service incrementally — each Phase 2 slice
    adds one more servicer. FastAPI keeps serving routes that have not yet
    moved; the gRPC port is additive until the last slice lands.
    """
    server = grpc.aio.server(interceptors=[ServerContextInterceptor()])
    maintenance_pb2_grpc.add_KnowledgeMaintenanceServiceServicer_to_server(
        MaintenanceServicer(), server
    )
    queue_pb2_grpc.add_KnowledgeQueueServiceServicer_to_server(
        QueueServicer(), server
    )
    ingest_pb2_grpc.add_KnowledgeIngestServiceServicer_to_server(
        IngestServicer(), server
    )
    graph_pb2_grpc.add_KnowledgeGraphServiceServicer_to_server(
        GraphServicer(), server
    )
    retrieve_pb2_grpc.add_KnowledgeRetrieveServiceServicer_to_server(
        RetrieveServicer(), server
    )

    service_names = (
        maintenance_pb2.DESCRIPTOR.services_by_name["KnowledgeMaintenanceService"].full_name,
        queue_pb2.DESCRIPTOR.services_by_name["KnowledgeQueueService"].full_name,
        ingest_pb2.DESCRIPTOR.services_by_name["KnowledgeIngestService"].full_name,
        graph_pb2.DESCRIPTOR.services_by_name["KnowledgeGraphService"].full_name,
        retrieve_pb2.DESCRIPTOR.services_by_name["KnowledgeRetrieveService"].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info(
        "gRPC KB services listening on :%d "
        "(Maintenance + Queue + Ingest[cpg,git-{structure,commits},purge] + Graph[alias] + Retrieve)",
        port,
    )
    return server
