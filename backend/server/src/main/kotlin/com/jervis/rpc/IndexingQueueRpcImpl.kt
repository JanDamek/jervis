package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.domain.PollingStatusEnum
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.indexing.CapabilityGroupDto
import com.jervis.dto.indexing.ClientItemGroupDto
import com.jervis.dto.indexing.ConnectionIndexingGroupDto
import com.jervis.dto.indexing.IndexingDashboardDto
import com.jervis.dto.indexing.IndexingItemType
import com.jervis.dto.indexing.IndexingQueueItemDto
import com.jervis.dto.indexing.IndexingQueuePageDto
import com.jervis.dto.indexing.PipelineItemDto
import com.jervis.dto.indexing.QualificationStepDto
import com.jervis.configuration.KbQueueItem
import com.jervis.configuration.KnowledgeServiceRestClient
import com.jervis.dto.indexing.KbQueueStatsDto
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.entity.TaskDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.integration.bugtracker.internal.entity.BugTrackerIssueIndexDocument
import com.jervis.integration.wiki.internal.entity.WikiPageIndexDocument
import com.jervis.repository.ClientRepository
import com.jervis.repository.ConnectionRepository
import com.jervis.repository.PollingStateRepository
import com.jervis.repository.ProjectRepository
import com.jervis.repository.TaskRepository
import com.jervis.service.IIndexingQueueService
import com.jervis.service.background.TaskService
import com.jervis.service.indexing.git.state.GitCommitDocument
import com.jervis.service.indexing.git.state.GitCommitState
import com.jervis.entity.email.EmailMessageIndexDocument
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

@Component
class IndexingQueueRpcImpl(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val connectionRepository: ConnectionRepository,
    private val clientRepository: ClientRepository,
    private val projectRepository: ProjectRepository,
    private val pollingStateRepository: PollingStateRepository,
    private val pollingIntervalRpc: PollingIntervalRpcImpl,
    private val taskRepository: TaskRepository,
    private val taskService: TaskService,
    private val kbClient: KnowledgeServiceRestClient,
) : IIndexingQueueService {

    private val logger = KotlinLogging.logger {}
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    override suspend fun getPendingItems(page: Int, pageSize: Int, search: String): IndexingQueuePageDto {
        return getItems(
            gitStates = listOf(GitCommitState.NEW, GitCommitState.INDEXING),
            pollingStates = listOf(PollingStatusEnum.NEW),
            page = page,
            pageSize = pageSize,
            search = search,
        )
    }

    override suspend fun getIndexedItems(page: Int, pageSize: Int, search: String): IndexingQueuePageDto {
        return getItems(
            gitStates = listOf(GitCommitState.INDEXED),
            pollingStates = listOf(PollingStatusEnum.INDEXED),
            page = page,
            pageSize = pageSize,
            search = search,
        )
    }

    override suspend fun getIndexingDashboard(
        search: String,
        kbPage: Int,
        kbPageSize: Int,
        clientFilter: String,
        projectFilter: String,
    ): IndexingDashboardDto {
        val now = Instant.now()

        // Build name caches
        val connections = connectionRepository.findAll().toList()
        val connectionMap = connections.associateBy { it.id }
        val clients = clientRepository.findAll().toList()
        val clientMap = clients.associateBy { it.id }
        val clientMapByObjectId = clients.associateBy { it.id.value }
        val projects = projectRepository.findAll().toList()
        val projectMap = projects.associateBy { it.id }
        val projectMapByObjectId = projects.associateBy { it.id.value }

        // Load polling states and interval settings
        val pollingStates = pollingStateRepository.findAll().toList()
        val pollingStatesByConnection = pollingStates.groupBy { it.connectionId }
        val intervalSettings = pollingIntervalRpc.getSettings()

        val query = search.trim().lowercase()
        val clientFilterQuery = clientFilter.trim().lowercase()
        val projectFilterQuery = projectFilter.trim().lowercase()

        // ── Collect pending items (NEW/INDEXING) ──
        val pendingItems = mutableListOf<IndexingQueueItemDto>()
        collectGitItems(pendingItems, listOf(GitCommitState.NEW, GitCommitState.INDEXING), clientMapByObjectId, projectMapByObjectId)
        collectEmailItems(pendingItems, listOf(PollingStatusEnum.NEW), connectionMap, clientMap, projectMap)
        collectBugTrackerItems(pendingItems, listOf(PollingStatusEnum.NEW), connectionMap, clientMap, projectMap)
        collectWikiItems(pendingItems, listOf(PollingStatusEnum.NEW), connectionMap, clientMap, projectMap)

        // Apply search filter
        val filteredPending = filterItems(pendingItems, query)

        // ── Build hierarchical connection → capability → client groups ──
        val connectionGroups = buildHierarchicalGroups(
            filteredPending, connectionMap, pollingStatesByConnection, intervalSettings,
        )

        // ── Collect pipeline items from tasks collection ──
        val pipelineResult = collectPipelineTasks(
            query, clientMap, connectionMap, now, kbPage, kbPageSize,
            clientFilterQuery, projectFilterQuery,
        )

        return IndexingDashboardDto(
            connectionGroups = connectionGroups,
            kbWaiting = pipelineResult.kbWaiting,
            kbWaitingTotalCount = pipelineResult.kbWaitingTotalCount,
            kbProcessing = pipelineResult.kbProcessing,
            kbProcessingCount = pipelineResult.kbProcessingCount,
            executionWaiting = pipelineResult.executionWaiting,
            executionWaitingCount = pipelineResult.executionWaitingCount,
            executionRunning = pipelineResult.executionRunning,
            executionRunningCount = pipelineResult.executionRunningCount,
            kbIndexed = pipelineResult.kbIndexed,
            kbIndexedTotalCount = pipelineResult.kbIndexedTotalCount,
            kbPage = kbPage,
            kbPageSize = kbPageSize,
            kbQueueStats = pipelineResult.kbQueueStats,
        )
    }

    override suspend fun triggerIndexNow(connectionId: String, capability: String): Boolean {
        logger.info { "Manual poll trigger: connectionId=$connectionId capability=$capability" }
        return pollingIntervalRpc.triggerPollNow(capability)
    }

    override suspend fun reorderKbQueueItem(taskId: String, newPosition: Int): Boolean {
        return try {
            val task = taskRepository.getById(TaskId(ObjectId(taskId))) ?: return false
            taskService.reorderTaskInQueue(task, newPosition)
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to reorder KB queue item: $taskId" }
            false
        }
    }

    override suspend fun prioritizeKbQueueItem(taskId: String): Boolean {
        return reorderKbQueueItem(taskId, 1)
    }

    override suspend fun processKbItemNow(taskId: String): Boolean {
        return try {
            val task = taskRepository.getById(TaskId(ObjectId(taskId))) ?: return false

            // Check if task is in READY_FOR_QUALIFICATION state
            if (task.state != TaskStateEnum.READY_FOR_QUALIFICATION) {
                logger.warn { "Cannot process task $taskId: not in READY_FOR_QUALIFICATION state (current: ${task.state})" }
                return false
            }

            // Check if another task is already being qualified (only one at a time)
            val qualifyingCount = taskRepository.countByState(TaskStateEnum.QUALIFYING)
            if (qualifyingCount > 0) {
                logger.warn { "Cannot process task $taskId: another task is already being qualified" }
                return false
            }

            // Change state to QUALIFYING to start KB processing immediately
            taskService.updateState(task, TaskStateEnum.QUALIFYING)
            logger.info { "Task $taskId marked for immediate KB processing" }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to process KB item now: $taskId" }
            false
        }
    }

    // ── Hierarchical grouping: connection → capability → client ──

    private fun buildHierarchicalGroups(
        items: List<IndexingQueueItemDto>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        pollingStatesByConnection: Map<ConnectionId, List<com.jervis.entity.polling.PollingStateDocument>>,
        intervalSettings: com.jervis.dto.indexing.PollingIntervalSettingsDto,
    ): List<ConnectionIndexingGroupDto> {
        // Group by connectionId
        val grouped = items.groupBy { it.connectionId }

        return grouped.map { (connId, connItems) ->
            val conn = if (connId.isNotEmpty()) connectionMap.values.find { it.id.value.toHexString() == connId } else null

            // Get polling states for this connection
            val connPollingStates = if (connId.isNotEmpty()) {
                try {
                    val connectionId = ConnectionId(ObjectId(connId))
                    pollingStatesByConnection[connectionId] ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val lastPolledAt = connPollingStates.maxByOrNull { it.lastUpdated }?.lastUpdated

            // Sub-group by capability (derived from item type)
            val byCapability = connItems.groupBy { itemTypeToCapability(it.type) }

            val capabilityGroups = byCapability.map { (capability, capItems) ->
                val intervalMinutes = intervalSettings.intervals.entries
                    .find { it.key.name == capability }?.value ?: 30
                val nextCheckAt = lastPolledAt?.plus(Duration.ofMinutes(intervalMinutes.toLong()))

                // Sub-group by client
                val byClient = capItems.groupBy { it.clientName }
                val clientGroups = byClient.map { (clientName, clientItems) ->
                    val sorted = clientItems.sortedByDescending { it.createdAt ?: "" }
                    ClientItemGroupDto(
                        clientId = clientItems.firstOrNull()?.let { findClientIdByName(clientName, clientItems) } ?: "",
                        clientName = clientName,
                        items = sorted.take(20),
                        totalItemCount = sorted.size,
                    )
                }.sortedByDescending { it.totalItemCount }

                CapabilityGroupDto(
                    capability = capability,
                    nextCheckAt = nextCheckAt?.formatIso(),
                    intervalMinutes = intervalMinutes,
                    clients = clientGroups,
                    totalItemCount = capItems.size,
                )
            }.sortedByDescending { it.totalItemCount }

            ConnectionIndexingGroupDto(
                connectionId = connId,
                connectionName = connItems.first().connectionName,
                provider = conn?.provider?.name ?: if (connId.isEmpty()) "GIT" else "UNKNOWN",
                lastPolledAt = lastPolledAt?.formatIso(),
                capabilityGroups = capabilityGroups,
                totalItemCount = connItems.size,
            )
        }.sortedByDescending { it.totalItemCount }
    }

    private fun itemTypeToCapability(type: IndexingItemType): String = when (type) {
        IndexingItemType.GIT_COMMIT -> "REPOSITORY"
        IndexingItemType.EMAIL -> "EMAIL_READ"
        IndexingItemType.BUGTRACKER_ISSUE -> "BUGTRACKER"
        IndexingItemType.WIKI_PAGE -> "WIKI"
    }

    private fun findClientIdByName(clientName: String, items: List<IndexingQueueItemDto>): String {
        // Items already have connectionId but not clientId directly; use first item's data
        return items.firstOrNull()?.id ?: ""
    }

    // ── Pipeline task collection (KB qualification + execution stages) ──

    private data class PipelineResult(
        val kbWaiting: List<PipelineItemDto>,
        val kbWaitingTotalCount: Long,
        val kbProcessing: List<PipelineItemDto>,
        val kbProcessingCount: Long,
        val executionWaiting: List<PipelineItemDto>,
        val executionWaitingCount: Long,
        val executionRunning: List<PipelineItemDto>,
        val executionRunningCount: Long,
        val kbIndexed: List<PipelineItemDto>,
        val kbIndexedTotalCount: Long,
        val kbQueueStats: KbQueueStatsDto? = null,
    )

    private suspend fun collectPipelineTasks(
        query: String,
        clientMap: Map<ClientId, ClientDocument>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        now: Instant,
        kbPage: Int,
        kbPageSize: Int,
        clientFilterQuery: String,
        projectFilterQuery: String,
    ): PipelineResult {
        val indexingTaskTypes = listOf(
            TaskTypeEnum.EMAIL_PROCESSING,
            TaskTypeEnum.BUGTRACKER_PROCESSING,
            TaskTypeEnum.WIKI_PROCESSING,
            TaskTypeEnum.GIT_PROCESSING,
            TaskTypeEnum.MEETING_PROCESSING,
            TaskTypeEnum.LINK_PROCESSING,
        )

        // ── KB queue from SQLite (the real processing queue) ──
        val kbQueueResponse = kbClient.getExtractionQueue(limit = 200)
        val kbStats = KbQueueStatsDto(
            total = kbQueueResponse.stats.total,
            pending = kbQueueResponse.stats.pending,
            inProgress = kbQueueResponse.stats.inProgress,
            failed = kbQueueResponse.stats.failed,
        )

        // Pre-load indexing tasks by correlationId for enrichment.
        // KB receives task.correlationId as sourceUrn (e.g. "email:698f..."),
        // NOT task.sourceUrn (e.g. "email::conn:xxx,msgId:yyy").
        val indexingActiveStates = listOf(
            TaskStateEnum.READY_FOR_QUALIFICATION,
            TaskStateEnum.QUALIFYING,
            TaskStateEnum.DONE,
        )
        val activeServerTasks = mongoTemplate.find(
            Query(
                Criteria.where("state").`in`(indexingActiveStates.map { it.name })
                    .and("type").`in`(indexingTaskTypes.map { it.name }),
            ),
            TaskDocument::class.java, "tasks",
        ).collectList().awaitSingle().associateBy { it.correlationId }

        // Split KB items into in_progress and pending
        val kbInProgress = kbQueueResponse.items.filter { it.status == "in_progress" }
        val kbPending = kbQueueResponse.items.filter { it.status == "pending" }

        // Server QUALIFYING tasks (RAG ingest + summary — first stage of KB processing)
        val qualifyingTasks = mongoTemplate.find(
            Query(
                Criteria.where("state").`is`(TaskStateEnum.QUALIFYING.name)
                    .and("type").`in`(indexingTaskTypes.map { it.name }),
            ).with(Sort.by(Sort.Direction.ASC, "qualificationStartedAt")),
            TaskDocument::class.java, "tasks",
        ).collectList().awaitSingle()

        // KB extraction IN_PROGRESS items exclude those that match a QUALIFYING server task
        // (they represent different stages — qualification = RAG+summary, extraction = graph)
        val qualifyingCorrelationIds = qualifyingTasks.map { it.correlationId }.toSet()
        val kbInProgressFiltered = kbInProgress.filter { it.sourceUrn !in qualifyingCorrelationIds }

        // Enrich KB items → PipelineItemDto
        val serverQualifyingItems = qualifyingTasks.map { task ->
            val clientName = clientMap[task.clientId]?.name ?: task.clientId.value.toHexString()
            val connName = extractConnectionName(task.sourceUrn.value, connectionMap)
            PipelineItemDto(
                id = task.id.value.toHexString(),
                type = taskTypeToItemType(task.type),
                title = extractTaskTitle(task),
                connectionName = connName,
                clientName = clientName,
                sourceUrn = task.sourceUrn.value,
                createdAt = task.createdAt.formatIso(),
                pipelineState = "QUALIFYING",
                retryCount = task.qualificationRetries,
                errorMessage = task.errorMessage,
                taskId = task.id.value.toHexString(),
                queuePosition = null,
                processingMode = task.processingMode.name,
                qualificationStartedAt = task.qualificationStartedAt?.formatIso(),
                qualificationSteps = task.qualificationSteps.map { step ->
                    QualificationStepDto(
                        timestamp = step.timestamp.formatIso(),
                        step = step.step,
                        message = step.message,
                        metadata = step.metadata,
                    )
                },
            )
        }

        val kbExtractionItems = kbInProgressFiltered.mapIndexed { index, item ->
            enrichKbItem(item, index, activeServerTasks, clientMap, connectionMap)
        }
        val kbProcessingAll = serverQualifyingItems + kbExtractionItems
        val kbWaitingAll = kbPending.mapIndexed { index, item ->
            enrichKbItem(item, index, activeServerTasks, clientMap, connectionMap)
        }

        // Execution Waiting: READY_FOR_GPU (from MongoDB — these are post-KB tasks)
        val executionWaitingAll = collectTasksByStates(
            states = listOf(TaskStateEnum.READY_FOR_GPU),
            types = indexingTaskTypes,
            clientMap = clientMap,
            connectionMap = connectionMap,
            pipelineState = "READY_FOR_GPU",
        )

        // Execution Running: DISPATCHED_GPU + PYTHON_ORCHESTRATING
        val executionRunningAll = collectTasksByStates(
            states = listOf(TaskStateEnum.DISPATCHED_GPU, TaskStateEnum.PYTHON_ORCHESTRATING),
            types = indexingTaskTypes,
            clientMap = clientMap,
            connectionMap = connectionMap,
            pipelineState = null,
        )

        // Apply search and client/project filters
        val filteredKbWaiting = filterPipelineItems(kbWaitingAll, query, clientFilterQuery, projectFilterQuery)
        val filteredKbProcessing = filterPipelineItems(kbProcessingAll, query, clientFilterQuery, projectFilterQuery)
        val filteredExecWaiting = filterPipelineItems(executionWaitingAll, query, clientFilterQuery, projectFilterQuery)
        val filteredExecRunning = filterPipelineItems(executionRunningAll, query, clientFilterQuery, projectFilterQuery)

        // Pagination for KB waiting
        val safeKbPage = kbPage.coerceAtLeast(0)
        val safeKbPageSize = kbPageSize.coerceIn(1, 100)
        val kbWaitingStart = safeKbPage * safeKbPageSize
        val pagedKbWaiting = if (kbWaitingStart >= filteredKbWaiting.size) {
            emptyList()
        } else {
            filteredKbWaiting.subList(kbWaitingStart, (kbWaitingStart + safeKbPageSize).coerceAtMost(filteredKbWaiting.size))
        }

        // Indexed: DB-level pagination
        val (pagedKbIndexed, kbIndexedTotalCount) = collectIndexedTasksPaginated(
            types = indexingTaskTypes,
            clientMap = clientMap,
            connectionMap = connectionMap,
            page = kbPage,
            pageSize = safeKbPageSize,
        )

        return PipelineResult(
            kbWaiting = pagedKbWaiting,
            kbWaitingTotalCount = filteredKbWaiting.size.toLong(),
            kbProcessing = filteredKbProcessing,
            kbProcessingCount = filteredKbProcessing.size.toLong(),
            executionWaiting = filteredExecWaiting,
            executionWaitingCount = filteredExecWaiting.size.toLong(),
            executionRunning = filteredExecRunning,
            executionRunningCount = filteredExecRunning.size.toLong(),
            kbIndexed = pagedKbIndexed,
            kbIndexedTotalCount = kbIndexedTotalCount,
            kbQueueStats = kbStats,
        )
    }

    /**
     * Enrich a KB SQLite queue item with server task info (if available).
     * Items from indexing pipeline have a matching server task with connection/client/type info.
     * Items from MCP/orchestrator (CRITICAL writes) may not have a server task.
     */
    private fun enrichKbItem(
        kbItem: KbQueueItem,
        position: Int,
        activeServerTasks: Map<String, TaskDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
    ): PipelineItemDto {
        val serverTask = activeServerTasks[kbItem.sourceUrn]
        val clientName = if (serverTask != null) {
            clientMap[serverTask.clientId]?.name ?: serverTask.clientId.value.toHexString()
        } else {
            // Try to resolve clientId from KB item
            try {
                val clientId = ClientId(ObjectId(kbItem.clientId))
                clientMap[clientId]?.name ?: kbItem.clientId
            } catch (_: Exception) {
                kbItem.clientId
            }
        }
        val connName = extractConnectionName(kbItem.sourceUrn, connectionMap)
        val itemType = if (serverTask != null) taskTypeToItemType(serverTask.type) else kindToItemType(kbItem.kind)

        val pipelineState = if (kbItem.status == "in_progress") "EXTRACTING" else "WAITING"

        val title = if (serverTask != null) {
            extractTaskTitle(serverTask)
        } else {
            // Extract meaningful part from sourceUrn
            kbItem.sourceUrn.substringAfter("::").take(100)
        }

        // For EXTRACTING items, use lastAttemptAt (when worker claimed the task) as start time.
        // For WAITING items, qualificationStartedAt from server task is irrelevant.
        val startedAt = if (pipelineState == "EXTRACTING") {
            kbItem.lastAttemptAt  // ISO 8601 from SQLite — when extraction actually started
        } else {
            serverTask?.qualificationStartedAt?.formatIso()
        }

        return PipelineItemDto(
            id = kbItem.taskId,
            type = itemType,
            title = title,
            connectionName = connName,
            clientName = clientName,
            sourceUrn = kbItem.sourceUrn,
            createdAt = kbItem.createdAt,
            pipelineState = pipelineState,
            retryCount = kbItem.attempts,
            errorMessage = kbItem.error,
            taskId = serverTask?.id?.value?.toHexString(),
            queuePosition = position + 1,
            processingMode = if (kbItem.priority == 0) "FOREGROUND" else "BACKGROUND",
            qualificationStartedAt = startedAt,
            qualificationSteps = serverTask?.qualificationSteps?.map { step ->
                QualificationStepDto(
                    timestamp = step.timestamp.formatIso(),
                    step = step.step,
                    message = step.message,
                    metadata = step.metadata,
                )
            } ?: emptyList(),
            extractionProgressCurrent = kbItem.progressCurrent,
            extractionProgressTotal = kbItem.progressTotal,
        )
    }

    private fun kindToItemType(kind: String?): IndexingItemType = when (kind) {
        "email" -> IndexingItemType.EMAIL
        "jira", "github-issue", "gitlab-issue" -> IndexingItemType.BUGTRACKER_ISSUE
        "confluence", "wiki" -> IndexingItemType.WIKI_PAGE
        "git_commit" -> IndexingItemType.GIT_COMMIT
        else -> IndexingItemType.WIKI_PAGE
    }

    private suspend fun collectTasksByStates(
        states: List<TaskStateEnum>,
        types: List<TaskTypeEnum>,
        clientMap: Map<ClientId, ClientDocument>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        pipelineState: String?,
    ): List<PipelineItemDto> {
        val taskQuery = Query(
            Criteria.where("state").`in`(states.map { it.name })
                .and("type").`in`(types.map { it.name }),
        ).with(Sort.by(Sort.Direction.ASC, "createdAt"))

        val tasks = mongoTemplate.find(taskQuery, TaskDocument::class.java, "tasks")
            .collectList().awaitSingle()

        return tasks.map { task ->
            val clientName = clientMap[task.clientId]?.name ?: task.clientId.value.toHexString()
            val connName = extractConnectionName(task.sourceUrn.value, connectionMap)
            val itemType = taskTypeToItemType(task.type)

            // Determine pipeline state
            val state = pipelineState ?: when {
                task.state == TaskStateEnum.READY_FOR_QUALIFICATION && task.qualificationRetries > 0 -> "RETRYING"
                task.state == TaskStateEnum.READY_FOR_QUALIFICATION -> "WAITING"
                else -> task.state.name
            }

            PipelineItemDto(
                id = task.id.value.toHexString(),
                type = itemType,
                title = extractTaskTitle(task),
                connectionName = connName,
                clientName = clientName,
                sourceUrn = task.sourceUrn.value,
                createdAt = task.createdAt.formatIso(),
                pipelineState = state,
                retryCount = task.qualificationRetries,
                nextRetryAt = task.nextQualificationRetryAt?.formatIso(),
                errorMessage = task.errorMessage,
                taskId = task.id.value.toHexString(),
                queuePosition = task.queuePosition,
                processingMode = task.processingMode.name,
                qualificationStartedAt = task.qualificationStartedAt?.formatIso(),
                qualificationSteps = task.qualificationSteps.map { step ->
                    QualificationStepDto(
                        timestamp = step.timestamp.formatIso(),
                        step = step.step,
                        message = step.message,
                        metadata = step.metadata,
                    )
                },
            )
        }
    }

    /**
     * DB-level paginated query for DONE/DISPATCHED_GPU tasks (Hotovo section).
     * Uses MongoDB skip/limit instead of loading all tasks into RAM.
     * Includes both DONE (info_only/simple_action) and DISPATCHED_GPU (legacy/orchestrated).
     */
    private suspend fun collectIndexedTasksPaginated(
        types: List<TaskTypeEnum>,
        clientMap: Map<ClientId, ClientDocument>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        page: Int,
        pageSize: Int,
    ): Pair<List<PipelineItemDto>, Long> {
        val criteria = Criteria.where("state").`in`(listOf(TaskStateEnum.DONE.name, TaskStateEnum.DISPATCHED_GPU.name))
            .and("type").`in`(types.map { it.name })

        // Count total (lightweight)
        val totalCount = mongoTemplate.count(Query(criteria), "tasks").awaitSingle()

        // Paginated fetch (newest first)
        val pagedQuery = Query(criteria)
            .with(Sort.by(Sort.Direction.DESC, "createdAt"))
            .skip((page * pageSize).toLong())
            .limit(pageSize)

        val tasks = mongoTemplate.find(pagedQuery, TaskDocument::class.java, "tasks")
            .collectList().awaitSingle()

        val items = tasks.map { task ->
            val clientName = clientMap[task.clientId]?.name ?: task.clientId.value.toHexString()
            val connName = extractConnectionName(task.sourceUrn.value, connectionMap)
            PipelineItemDto(
                id = task.id.value.toHexString(),
                type = taskTypeToItemType(task.type),
                title = extractTaskTitle(task),
                connectionName = connName,
                clientName = clientName,
                sourceUrn = task.sourceUrn.value,
                createdAt = task.createdAt.formatIso(),
                pipelineState = "INDEXED",
                retryCount = 0,
                nextRetryAt = null,
                errorMessage = null,
                taskId = task.id.value.toHexString(),
                queuePosition = null,
                qualificationStartedAt = task.qualificationStartedAt?.formatIso(),
                qualificationSteps = task.qualificationSteps.map { step ->
                    QualificationStepDto(
                        timestamp = step.timestamp.formatIso(),
                        step = step.step,
                        message = step.message,
                        metadata = step.metadata,
                    )
                },
            )
        }

        return items to totalCount
    }

    private fun taskTypeToItemType(type: TaskTypeEnum): IndexingItemType = when (type) {
        TaskTypeEnum.GIT_PROCESSING -> IndexingItemType.GIT_COMMIT
        TaskTypeEnum.EMAIL_PROCESSING -> IndexingItemType.EMAIL
        TaskTypeEnum.BUGTRACKER_PROCESSING -> IndexingItemType.BUGTRACKER_ISSUE
        TaskTypeEnum.WIKI_PROCESSING -> IndexingItemType.WIKI_PAGE
        else -> IndexingItemType.WIKI_PAGE // fallback for LINK_PROCESSING, MEETING_PROCESSING
    }

    private fun extractTaskTitle(task: TaskDocument): String {
        // Extract meaningful title from task content (first line or taskName)
        if (task.taskName != "Unnamed Task") return task.taskName
        val firstLine = task.content.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
        return firstLine ?: task.correlationId
    }

    private fun extractConnectionName(
        sourceUrnValue: String,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
    ): String {
        // Parse connection ID from sourceUrn: "jira::conn:abc123,issueKey:..." → find connection name
        val connHex = Regex("conn:([a-f0-9]+)").find(sourceUrnValue)?.groupValues?.getOrNull(1)
        if (connHex != null) {
            try {
                val connId = ConnectionId(ObjectId(connHex))
                connectionMap[connId]?.name?.let { return it }
            } catch (_: Exception) {
                // ignore
            }
        }
        // Fallback: extract prefix
        val prefix = sourceUrnValue.substringBefore("::")
        return when (prefix) {
            "jira" -> "Jira"
            "github-issue" -> "GitHub"
            "gitlab-issue" -> "GitLab"
            "confluence" -> "Confluence"
            "email" -> "Email"
            "git" -> "Git"
            else -> prefix
        }
    }

    private fun filterPipelineItems(
        items: List<PipelineItemDto>,
        query: String,
        clientFilterQuery: String,
        projectFilterQuery: String,
    ): List<PipelineItemDto> {
        return items.filter { item ->
            // Search filter
            val matchesSearch = query.isBlank() || item.title.lowercase().contains(query) ||
                item.connectionName.lowercase().contains(query) ||
                item.clientName.lowercase().contains(query) ||
                (item.sourceUrn?.lowercase()?.contains(query) == true)

            // Client filter
            val matchesClient = clientFilterQuery.isBlank() ||
                item.clientName.lowercase().contains(clientFilterQuery)

            // Project filter (check in title and sourceUrn - projects are encoded in URN)
            val matchesProject = projectFilterQuery.isBlank() ||
                item.title.lowercase().contains(projectFilterQuery) ||
                (item.sourceUrn?.lowercase()?.contains(projectFilterQuery) == true)

            matchesSearch && matchesClient && matchesProject
        }
    }

    // ── Pending item collectors (with correct sourceUrn) ──

    private suspend fun collectGitItems(
        items: MutableList<IndexingQueueItemDto>,
        states: List<GitCommitState>,
        clientMapByObjectId: Map<ObjectId, ClientDocument>,
        projectMapByObjectId: Map<ObjectId, ProjectDocument>,
    ) {
        val gitQuery = Query(Criteria.where("state").`in`(states.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "commitDate"))
        val gitDocs = mongoTemplate.find(gitQuery, GitCommitDocument::class.java, "git_commits")
            .collectList().awaitSingle()

        for (doc in gitDocs) {
            val clientName = doc.clientId.let { clientMapByObjectId[it]?.name } ?: doc.clientId.toHexString()
            val projectName = doc.projectId?.let { projectMapByObjectId[it]?.name }
            val sourceUrn = doc.projectId?.let {
                SourceUrn.git(ProjectId(it), doc.commitHash).value
            }

            items += IndexingQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.GIT_COMMIT,
                connectionName = "Git",
                connectionId = "",
                clientName = clientName,
                projectName = projectName,
                title = doc.message?.take(120) ?: doc.commitHash.take(8),
                createdAt = doc.commitDate?.formatIso(),
                state = doc.state.name,
                errorMessage = null,
                sourceUrn = sourceUrn,
            )
        }
    }

    private suspend fun collectEmailItems(
        items: MutableList<IndexingQueueItemDto>,
        states: List<PollingStatusEnum>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        projectMap: Map<ProjectId, ProjectDocument>,
    ) {
        val emailQuery = Query(Criteria.where("state").`in`(states.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "receivedDate"))
        val emailDocs = mongoTemplate.find(emailQuery, EmailMessageIndexDocument::class.java, "email_message_index")
            .collectList().awaitSingle()

        for (doc in emailDocs) {
            val connName = connectionMap[doc.connectionId]?.name ?: "Email"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val projectName = doc.projectId?.let { projectMap[it]?.name }
            val sourceUrn = doc.messageId?.let {
                SourceUrn.email(doc.connectionId, it, doc.subject ?: "").value
            }

            items += IndexingQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.EMAIL,
                connectionName = connName,
                connectionId = doc.connectionId.value.toHexString(),
                clientName = clientName,
                projectName = projectName,
                title = doc.subject ?: "(bez předmětu)",
                createdAt = doc.receivedDate.formatIso(),
                state = doc.state.name,
                errorMessage = doc.indexingError,
                sourceUrn = sourceUrn,
            )
        }
    }

    private suspend fun collectBugTrackerItems(
        items: MutableList<IndexingQueueItemDto>,
        states: List<PollingStatusEnum>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        projectMap: Map<ProjectId, ProjectDocument>,
    ) {
        val btQuery = Query(Criteria.where("status").`in`(states.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "bugtrackerUpdatedAt"))
        val btDocs = mongoTemplate.find(btQuery, BugTrackerIssueIndexDocument::class.java, "bugtracker_issues")
            .collectList().awaitSingle()

        for (doc in btDocs) {
            val conn = connectionMap[doc.connectionId]
            val connName = conn?.name ?: "Bugtracker"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val projectName = doc.projectId?.let { projectMap[it]?.name }

            // FIX: Use correct sourceUrn based on provider (was hardcoded to Jira)
            val sourceUrn = buildBugTrackerSourceUrn(conn?.provider, doc.connectionId.value, doc.issueKey)

            items += IndexingQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.BUGTRACKER_ISSUE,
                connectionName = connName,
                connectionId = doc.connectionId.value.toHexString(),
                clientName = clientName,
                projectName = projectName,
                title = listOfNotNull(doc.issueKey, doc.summary).joinToString(" – "),
                createdAt = doc.bugtrackerUpdatedAt.formatIso(),
                state = doc.status.name,
                errorMessage = doc.indexingError,
                sourceUrn = sourceUrn,
            )
        }
    }

    private suspend fun collectWikiItems(
        items: MutableList<IndexingQueueItemDto>,
        states: List<PollingStatusEnum>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        projectMap: Map<ProjectId, ProjectDocument>,
    ) {
        val wikiQuery = Query(Criteria.where("status").`in`(states.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "wikiUpdatedAt"))
        val wikiDocs = mongoTemplate.find(wikiQuery, WikiPageIndexDocument::class.java, "wiki_pages")
            .collectList().awaitSingle()

        for (doc in wikiDocs) {
            val connName = connectionMap[doc.connectionDocumentId]?.name ?: "Wiki"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val projectName = doc.projectId?.let { projectMap[it]?.name }
            val sourceUrn = SourceUrn.confluence(doc.connectionDocumentId.value, doc.pageId).value

            items += IndexingQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.WIKI_PAGE,
                connectionName = connName,
                connectionId = doc.connectionDocumentId.value.toHexString(),
                clientName = clientName,
                projectName = projectName,
                title = doc.title,
                createdAt = doc.wikiUpdatedAt.formatIso(),
                state = doc.status.name,
                errorMessage = doc.indexingError,
                sourceUrn = sourceUrn,
            )
        }
    }

    // ── Source URN helpers ──

    /**
     * Build correct sourceUrn for bugtracker items based on provider.
     * Fixes bug where all bugtracker items were hardcoded to Jira URN.
     */
    private fun buildBugTrackerSourceUrn(provider: ProviderEnum?, connectionId: ObjectId, issueKey: String): String =
        when (provider) {
            ProviderEnum.GITHUB -> SourceUrn.githubIssue(connectionId, issueKey).value
            ProviderEnum.GITLAB -> SourceUrn.gitlabIssue(connectionId, issueKey).value
            else -> SourceUrn.jira(connectionId, issueKey).value
        }

    // ── Legacy flat-list implementation (kept for backward compatibility) ──

    private suspend fun getItems(
        gitStates: List<GitCommitState>,
        pollingStates: List<PollingStatusEnum>,
        page: Int,
        pageSize: Int,
        search: String,
    ): IndexingQueuePageDto {
        val connections = connectionRepository.findAll().toList()
        val connectionMap = connections.associateBy { it.id }
        val clients = clientRepository.findAll().toList()
        val clientMap = clients.associateBy { it.id }
        val clientMapByObjectId = clients.associateBy { it.id.value }
        val projects = projectRepository.findAll().toList()
        val projectMap = projects.associateBy { it.id }
        val projectMapByObjectId = projects.associateBy { it.id.value }

        val allItems = mutableListOf<IndexingQueueItemDto>()
        collectGitItems(allItems, gitStates, clientMapByObjectId, projectMapByObjectId)
        collectEmailItems(allItems, pollingStates, connectionMap, clientMap, projectMap)
        collectBugTrackerItems(allItems, pollingStates, connectionMap, clientMap, projectMap)
        collectWikiItems(allItems, pollingStates, connectionMap, clientMap, projectMap)

        val filtered = filterItems(allItems, search.trim().lowercase())
        val sorted = filtered.sortedByDescending { it.createdAt ?: "" }

        val safePage = page.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(1, 100)
        val start = safePage * safePageSize
        val paged = if (start >= sorted.size) emptyList() else sorted.subList(start, (start + safePageSize).coerceAtMost(sorted.size))

        return IndexingQueuePageDto(
            items = paged,
            totalCount = sorted.size.toLong(),
            page = safePage,
            pageSize = safePageSize,
        )
    }

    private fun filterItems(items: List<IndexingQueueItemDto>, query: String): List<IndexingQueueItemDto> {
        if (query.isBlank()) return items
        return items.filter { item ->
            item.title.lowercase().contains(query) ||
                item.connectionName.lowercase().contains(query) ||
                item.clientName.lowercase().contains(query) ||
                (item.projectName?.lowercase()?.contains(query) == true)
        }
    }

    private fun Instant.formatIso(): String = isoFormatter.format(this)
}
