package com.jervis.infrastructure.grpc

import com.jervis.agent.PythonOrchestratorClient
import com.jervis.agent.QualifyAttachmentDto
import com.jervis.agent.QualifyRequestDto
import com.jervis.common.types.TaskId
import com.jervis.contracts.server.AckResponse
import com.jervis.contracts.server.KbDoneRequest
import com.jervis.contracts.server.KbProgressRequest
import com.jervis.contracts.server.ServerKbCallbacksServiceGrpcKt
import com.jervis.dto.filtering.FilterAction
import com.jervis.dto.task.TaskStateEnum
import com.jervis.filtering.FilteringRulesService
import com.jervis.qualifier.KbDoneRouter
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.task.QualificationStepRecord
import com.jervis.task.TaskRepository
import com.jervis.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ServerKbCallbacksGrpcImpl(
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val filteringRulesService: FilteringRulesService,
    private val pythonOrchestratorClient: PythonOrchestratorClient,
) : ServerKbCallbacksServiceGrpcKt.ServerKbCallbacksServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun kbProgress(request: KbProgressRequest): AckResponse {
        return try {
            backgroundScope.launch {
                val taskId = TaskId(ObjectId(request.taskId))
                val metadata = request.metadataMap + ("agent" to "simple_qualifier")
                taskService.appendQualificationStep(
                    taskId,
                    QualificationStepRecord(
                        timestamp = Instant.now(),
                        step = request.step,
                        message = request.message,
                        metadata = metadata,
                    ),
                )
                notificationRpc.emitQualificationProgress(
                    taskId = request.taskId,
                    clientId = request.clientId,
                    message = request.message,
                    step = request.step,
                    metadata = metadata,
                )
            }
            AckResponse.newBuilder().setOk(true).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to process KB progress callback" }
            AckResponse.newBuilder().setOk(false).setError(e.message.orEmpty()).build()
        }
    }

    override suspend fun kbDone(request: KbDoneRequest): AckResponse {
        logger.info { "KB_DONE_CALLBACK: taskId=${request.taskId} status=${request.status}" }
        backgroundScope.launch {
            try {
                val taskId = TaskId(ObjectId(request.taskId))
                val task = taskRepository.getById(taskId) ?: run {
                    logger.error { "KB_DONE_CALLBACK: task not found taskId=${request.taskId}" }
                    return@launch
                }

                if (task.state != TaskStateEnum.INDEXING) {
                    logger.warn {
                        "KB_DONE_CALLBACK: task not in INDEXING state taskId=${request.taskId} state=${task.state}"
                    }
                    return@launch
                }

                if (request.status == "error") {
                    val errorMsg = request.error.takeIf { it.isNotBlank() } ?: "KB processing failed"
                    logger.error { "KB_DONE_CALLBACK: KB reported error taskId=${request.taskId} error=$errorMsg" }
                    taskService.markAsError(task, errorMsg)
                    taskService.appendQualificationStep(
                        taskId,
                        QualificationStepRecord(
                            timestamp = Instant.now(),
                            step = "error",
                            message = "KB chyba: $errorMsg",
                            metadata = mapOf("agent" to "simple_qualifier"),
                        ),
                    )
                    notificationRpc.emitQualificationProgress(
                        taskId = request.taskId,
                        clientId = request.clientId,
                        message = "KB chyba: $errorMsg",
                        step = "error",
                        metadata = mapOf("agent" to "simple_qualifier"),
                    )
                    return@launch
                }

                if (!request.hasResult()) {
                    taskService.markAsError(task, "KB returned done but no result")
                    return@launch
                }
                val r = request.result

                taskService.saveKbResult(
                    taskId,
                    kbSummary = r.summary,
                    kbEntities = r.entitiesList,
                    kbActionable = false,
                )

                val filterAction = try {
                    val sourceType = KbDoneRouter.extractSourceType(task.sourceUrn)
                    filteringRulesService.evaluate(
                        sourceType = sourceType,
                        subject = r.summary,
                        body = task.content,
                        labels = r.entitiesList,
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "KB_DONE_CALLBACK: filter eval failed taskId=${request.taskId} (non-fatal)" }
                    null
                }

                if (filterAction == FilterAction.IGNORE && !task.mentionsJervis) {
                    taskService.updateState(task, TaskStateEnum.DONE)
                    logger.info { "KB_DONE_CALLBACK: filtered IGNORE taskId=${request.taskId} (user rule)" }
                    return@launch
                }

                try {
                    val qualifyRequest = QualifyRequestDto(
                        taskId = request.taskId,
                        clientId = request.clientId,
                        sourceUrn = task.sourceUrn.value,
                        summary = r.summary,
                        entities = r.entitiesList,
                        suggestedActions = r.suggestedActionsList,
                        urgency = r.urgency,
                        actionType = r.actionType.takeIf { it.isNotBlank() },
                        estimatedComplexity = r.estimatedComplexity.takeIf { it.isNotBlank() },
                        isAssignedToMe = r.isAssignedToMe,
                        hasFutureDeadline = r.hasFutureDeadline,
                        suggestedDeadline = r.suggestedDeadline.takeIf { it.isNotBlank() },
                        suggestedAgent = r.suggestedAgent.takeIf { it.isNotBlank() },
                        affectedFiles = r.affectedFilesList,
                        relatedKbNodes = r.relatedKbNodesList,
                        hasAttachments = task.hasAttachments,
                        attachmentCount = task.attachmentCount,
                        attachments = task.attachments.mapIndexed { idx, att ->
                            QualifyAttachmentDto(
                                filename = att.filename,
                                contentType = att.mimeType,
                                size = att.sizeBytes,
                                index = idx,
                            )
                        },
                        content = task.content,
                        mentionsJervis = task.mentionsJervis,
                    )
                    val qualifyResponse = pythonOrchestratorClient.qualify(qualifyRequest)
                    if (qualifyResponse != null) {
                        logger.info {
                            "KB_DONE_CALLBACK: taskId=${request.taskId} → dispatched to /qualify thread=${qualifyResponse.threadId}"
                        }
                    } else {
                        taskService.updateState(task, TaskStateEnum.QUEUED)
                        logger.warn { "KB_DONE_CALLBACK: /qualify returned null, fallback to QUEUED" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "KB_DONE_CALLBACK: /qualify dispatch failed, fallback to QUEUED" }
                    taskService.updateState(task, TaskStateEnum.QUEUED)
                }
            } catch (e: Exception) {
                logger.error(e) { "KB_DONE_CALLBACK: failed to process result taskId=${request.taskId}" }
            }
        }
        return AckResponse.newBuilder().setOk(true).build()
    }
}
