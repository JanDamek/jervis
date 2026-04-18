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

from jervis.knowledgebase import ingest_pb2, ingest_pb2_grpc
from jervis.knowledgebase import maintenance_pb2, maintenance_pb2_grpc
from jervis.knowledgebase import queue_pb2, queue_pb2_grpc
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

    service_names = (
        maintenance_pb2.DESCRIPTOR.services_by_name["KnowledgeMaintenanceService"].full_name,
        queue_pb2.DESCRIPTOR.services_by_name["KnowledgeQueueService"].full_name,
        ingest_pb2.DESCRIPTOR.services_by_name["KnowledgeIngestService"].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info(
        "gRPC KB services listening on :%d "
        "(Maintenance + Queue + Ingest[cpg,git-structure,git-commits])",
        port,
    )
    return server
