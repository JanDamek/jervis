package com.jervis.service.action

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.TaskId
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.pipeline.ActionExecutionRequest
import com.jervis.dto.pipeline.ActionExecutionResult
import com.jervis.dto.pipeline.ApprovalAction
import com.jervis.dto.pipeline.ApprovalDecision
import com.jervis.dto.pipeline.ApprovalQueueItem
import com.jervis.dto.guidelines.ApprovalRule
import com.jervis.entity.TaskDocument
import com.jervis.entity.ApprovalQueueDocument
import com.jervis.entity.ApprovalStatisticsDocument
import com.jervis.repository.ApprovalQueueRepository
import com.jervis.repository.ApprovalStatisticsRepository
import com.jervis.repository.TaskRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.service.guidelines.GuidelinesService
import com.jervis.service.task.UserTaskService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * EPIC 5: Action Execution Engine — centralized service for executing approved actions.
 *
 * Every write action in JERVIS flows through this service:
 * 1. Evaluate approval (via guidelines rules)
 * 2. If AUTO_APPROVED → execute immediately
 * 3. If NEEDS_APPROVAL → create USER_TASK and wait for user
 * 4. If DENIED → reject and log
 *
 * Actions are routed to the appropriate backend service based on type:
 * - GIT_* → Python orchestrator (via existing dispatch)
 * - EMAIL_* → Email service
 * - JIRA_* → Brain client (internal Jira API)
 * - CONFLUENCE_* → Brain client (internal Confluence API)
 * - PR_* → Git provider service
 * - KB_* → Knowledge Base service
 * - CODING_DISPATCH → K8s Job dispatch
 */
@Service
class ActionExecutorService(
    private val taskRepository: TaskRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val userTaskService: UserTaskService,
    private val guidelinesService: GuidelinesService,
    private val brainWriteService: com.jervis.service.brain.BrainWriteService,
    private val approvalQueueRepository: ApprovalQueueRepository,
    private val approvalStatisticsRepository: ApprovalStatisticsRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Execute an action request. Evaluates approval first, then dispatches.
     *
     * @return ActionExecutionResult with success/failure and optional artifactId
     */
    suspend fun executeAction(request: ActionExecutionRequest): ActionExecutionResult {
        logger.info {
            "ACTION_EXECUTE: action=${request.action} clientId=${request.clientId} " +
                "projectId=${request.projectId} correlationId=${request.correlationId}"
        }

        // Step 1: Evaluate approval
        val decision = evaluateApproval(
            action = request.action,
            payload = request.payload,
            clientId = request.clientId,
            projectId = request.projectId,
        )

        return when (decision) {
            ApprovalDecision.AUTO_APPROVED -> {
                logger.info { "ACTION_AUTO_APPROVED: ${request.action}" }
                dispatchAction(request)
            }

            ApprovalDecision.NEEDS_APPROVAL -> {
                logger.info { "ACTION_NEEDS_APPROVAL: ${request.action}" }
                queueForApproval(request)
            }

            ApprovalDecision.DENIED -> {
                logger.info { "ACTION_DENIED: ${request.action}" }
                ActionExecutionResult(
                    success = false,
                    message = "Action denied by guidelines: ${request.action}",
                    action = request.action,
                )
            }
        }
    }

    /**
     * Handle user's approval response for a queued action.
     *
     * @param taskId The USER_TASK that was waiting for approval
     * @param approved Whether user approved or rejected
     * @param modifications Optional user modifications to the action payload
     */
    suspend fun handleApprovalResponse(
        taskId: TaskId,
        approved: Boolean,
        modifications: String? = null,
    ): ActionExecutionResult {
        val task = taskRepository.getById(taskId)
            ?: return ActionExecutionResult(
                success = false,
                message = "Task not found: $taskId",
                action = ApprovalAction.CHAT_REPLY,
            )

        if (task.state != TaskStateEnum.USER_TASK) {
            return ActionExecutionResult(
                success = false,
                message = "Task is not in USER_TASK state: ${task.state}",
                action = ApprovalAction.CHAT_REPLY,
            )
        }

        // E4-S3: Update MongoDB queue status
        try {
            val queueDoc = approvalQueueRepository.findByTaskId(taskId.toString())
            if (queueDoc != null) {
                approvalQueueRepository.save(
                    queueDoc.copy(
                        status = if (approved) "APPROVED" else "DENIED",
                        respondedAt = java.time.Instant.now(),
                    ),
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to update approval queue status (non-blocking)" }
        }

        if (!approved) {
            // User rejected — mark task as done
            taskRepository.save(task.copy(state = TaskStateEnum.DONE))
            logger.info { "ACTION_USER_REJECTED: taskId=$taskId" }
            return ActionExecutionResult(
                success = false,
                message = "Action rejected by user",
                action = ApprovalAction.CHAT_REPLY,
            )
        }

        // User approved — move task back to READY_FOR_GPU for execution
        val updatedTask = task.copy(
            state = TaskStateEnum.READY_FOR_GPU,
            pendingUserQuestion = null,
            userQuestionContext = null,
        )
        taskRepository.save(updatedTask)

        logger.info { "ACTION_USER_APPROVED: taskId=$taskId" }
        return ActionExecutionResult(
            success = true,
            message = "Action approved, dispatched for execution",
            action = ApprovalAction.CHAT_REPLY,
            artifactId = taskId.toString(),
        )
    }

    /**
     * Evaluate whether an action needs approval based on guidelines.
     */
    suspend fun evaluateApproval(
        action: ApprovalAction,
        payload: Map<String, String>,
        clientId: String,
        projectId: String?,
    ): ApprovalDecision {
        // DEPLOY and KB_DELETE always need approval
        if (action == ApprovalAction.DEPLOY || action == ApprovalAction.KB_DELETE) {
            return ApprovalDecision.NEEDS_APPROVAL
        }

        // Load merged guidelines
        val guidelines = try {
            guidelinesService.getMergedGuidelines(
                clientId = ClientId.fromString(clientId),
                projectId = projectId?.let { ProjectId.fromString(it) },
            )
        } catch (e: Exception) {
            logger.warn { "Failed to load guidelines for approval: ${e.message}" }
            return ApprovalDecision.NEEDS_APPROVAL // Fail safe
        }

        val approvalRules = guidelines.approval

        // Map action to the corresponding ApprovalRule
        val rule: ApprovalRule = when (action) {
            ApprovalAction.GIT_COMMIT -> approvalRules.autoApproveCommit
            ApprovalAction.GIT_PUSH -> approvalRules.autoApprovePush
            ApprovalAction.GIT_CREATE_BRANCH -> approvalRules.autoApproveCommit // branch ≈ commit
            ApprovalAction.EMAIL_SEND, ApprovalAction.EMAIL_REPLY -> approvalRules.autoApproveEmail
            ApprovalAction.JIRA_CREATE_ISSUE -> approvalRules.autoApproveJiraCreate
            ApprovalAction.JIRA_UPDATE_ISSUE, ApprovalAction.JIRA_COMMENT,
            ApprovalAction.JIRA_TRANSITION -> approvalRules.autoApproveJiraComment
            ApprovalAction.CONFLUENCE_CREATE_PAGE,
            ApprovalAction.CONFLUENCE_UPDATE_PAGE -> approvalRules.autoApproveConfluenceUpdate
            ApprovalAction.PR_CREATE, ApprovalAction.PR_COMMENT -> approvalRules.autoApprovePrComment
            ApprovalAction.PR_MERGE -> approvalRules.autoApprovePush // merge ≈ push
            ApprovalAction.CHAT_REPLY -> approvalRules.autoApproveChatReply
            ApprovalAction.CODING_DISPATCH -> approvalRules.autoApproveCodingDispatch
            ApprovalAction.KB_STORE -> return ApprovalDecision.AUTO_APPROVED // KB store is safe
            else -> return ApprovalDecision.NEEDS_APPROVAL
        }

        if (!rule.enabled) {
            return ApprovalDecision.NEEDS_APPROVAL
        }

        return ApprovalDecision.AUTO_APPROVED
    }

    /**
     * Dispatch an approved action to the appropriate backend service.
     */
    private suspend fun dispatchAction(request: ActionExecutionRequest): ActionExecutionResult {
        return try {
            when (request.action) {
                // Git operations are handled by the Python orchestrator
                ApprovalAction.GIT_COMMIT,
                ApprovalAction.GIT_PUSH,
                ApprovalAction.GIT_CREATE_BRANCH,
                -> dispatchGitAction(request)

                // Email operations
                ApprovalAction.EMAIL_SEND,
                ApprovalAction.EMAIL_REPLY,
                -> dispatchEmailAction(request)

                // Jira operations (via brain client)
                ApprovalAction.JIRA_CREATE_ISSUE,
                ApprovalAction.JIRA_UPDATE_ISSUE,
                ApprovalAction.JIRA_COMMENT,
                ApprovalAction.JIRA_TRANSITION,
                -> dispatchJiraAction(request)

                // Confluence operations (via brain client)
                ApprovalAction.CONFLUENCE_CREATE_PAGE,
                ApprovalAction.CONFLUENCE_UPDATE_PAGE,
                -> dispatchConfluenceAction(request)

                // PR operations
                ApprovalAction.PR_CREATE,
                ApprovalAction.PR_COMMENT,
                ApprovalAction.PR_MERGE,
                -> dispatchPrAction(request)

                // Chat reply (auto-approved, just logs)
                ApprovalAction.CHAT_REPLY -> ActionExecutionResult(
                    success = true,
                    message = "Chat reply approved",
                    action = request.action,
                )

                // KB operations
                ApprovalAction.KB_DELETE,
                ApprovalAction.KB_STORE,
                -> dispatchKbAction(request)

                // Deploy
                ApprovalAction.DEPLOY -> ActionExecutionResult(
                    success = false,
                    message = "Deploy action requires manual execution",
                    action = request.action,
                )

                // Coding dispatch
                ApprovalAction.CODING_DISPATCH -> ActionExecutionResult(
                    success = true,
                    message = "Coding agent dispatch approved",
                    action = request.action,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "ACTION_DISPATCH_FAILED: ${request.action}: ${e.message}" }
            ActionExecutionResult(
                success = false,
                message = "Action dispatch failed: ${e.message}",
                action = request.action,
            )
        }
    }

    /**
     * Queue an action for user approval — creates a USER_TASK.
     */
    private suspend fun queueForApproval(request: ActionExecutionRequest): ActionExecutionResult {
        val preview = buildApprovalPreview(request)

        val queueItem = ApprovalQueueItem(
            taskId = request.correlationId ?: "action-${System.currentTimeMillis()}",
            action = request.action,
            preview = preview,
            context = request.approvalContext ?: "Action requires approval",
            riskLevel = "MEDIUM",
            payload = request.payload,
        )

        // E4-S3: Persist to MongoDB for restart resilience
        try {
            approvalQueueRepository.save(
                ApprovalQueueDocument(
                    taskId = queueItem.taskId,
                    clientId = request.clientId,
                    projectId = request.projectId,
                    action = request.action.name,
                    preview = preview,
                    context = request.approvalContext ?: "Action requires approval",
                    riskLevel = "MEDIUM",
                    payload = request.payload,
                    status = "PENDING",
                ),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist approval queue item (non-blocking)" }
        }

        // Emit approval notification to connected clients
        notificationRpc.emitEvent(
            clientId = request.clientId,
            event = com.jervis.dto.events.JervisEvent.ApprovalRequired(
                clientId = request.clientId,
                taskId = queueItem.taskId,
                action = request.action.name,
                preview = preview,
                context = request.approvalContext ?: "",
                timestamp = java.time.Instant.now().toString(),
            ),
        )

        logger.info { "ACTION_QUEUED: ${request.action} taskId=${queueItem.taskId}" }

        return ActionExecutionResult(
            success = true,
            message = "Action queued for approval: ${request.action}",
            action = request.action,
            artifactId = queueItem.taskId,
        )
    }

    /**
     * Build human-readable preview of the action for approval UI.
     */
    private fun buildApprovalPreview(request: ActionExecutionRequest): String {
        val payload = request.payload
        return when (request.action) {
            ApprovalAction.GIT_COMMIT -> "Git commit: ${payload["message"]?.take(100) ?: "N/A"}"
            ApprovalAction.GIT_PUSH -> "Git push: ${payload["branch"] ?: "current branch"}"
            ApprovalAction.GIT_CREATE_BRANCH -> "Create branch: ${payload["branch_name"] ?: "N/A"}"
            ApprovalAction.EMAIL_SEND -> "Send email to: ${payload["to"] ?: "N/A"}"
            ApprovalAction.EMAIL_REPLY -> "Reply to email: ${payload["subject"]?.take(80) ?: "N/A"}"
            ApprovalAction.JIRA_CREATE_ISSUE -> "Create Jira issue: ${payload["summary"]?.take(80) ?: "N/A"}"
            ApprovalAction.JIRA_UPDATE_ISSUE -> "Update issue: ${payload["issue_key"] ?: "N/A"}"
            ApprovalAction.JIRA_COMMENT -> "Comment on: ${payload["issue_key"] ?: "N/A"}"
            ApprovalAction.JIRA_TRANSITION -> "Transition: ${payload["issue_key"] ?: "N/A"} → ${payload["transition"] ?: "N/A"}"
            ApprovalAction.CONFLUENCE_CREATE_PAGE -> "Create page: ${payload["title"]?.take(80) ?: "N/A"}"
            ApprovalAction.CONFLUENCE_UPDATE_PAGE -> "Update page: ${payload["page_id"] ?: "N/A"}"
            ApprovalAction.PR_CREATE -> "Create PR: ${payload["title"]?.take(80) ?: "N/A"}"
            ApprovalAction.PR_COMMENT -> "PR comment: ${payload["pr_id"] ?: "N/A"}"
            ApprovalAction.PR_MERGE -> "Merge PR: ${payload["pr_id"] ?: "N/A"}"
            ApprovalAction.CHAT_REPLY -> "Chat reply"
            ApprovalAction.KB_DELETE -> "Delete KB entry: ${payload["source_urn"] ?: "N/A"}"
            ApprovalAction.KB_STORE -> "Store to KB: ${payload["subject"]?.take(80) ?: "N/A"}"
            ApprovalAction.DEPLOY -> "Deploy: ${payload["target"] ?: "N/A"}"
            ApprovalAction.CODING_DISPATCH -> "Dispatch coding agent: ${payload["task"]?.take(80) ?: "N/A"}"
        }
    }

    // --- Action dispatch methods (delegate to existing services) ---

    private suspend fun dispatchGitAction(request: ActionExecutionRequest): ActionExecutionResult {
        // Git actions are handled by the Python orchestrator's git agent
        // This service just records the approval — actual execution happens in orchestrator
        return ActionExecutionResult(
            success = true,
            message = "Git action approved for execution by orchestrator",
            action = request.action,
        )
    }

    private suspend fun dispatchEmailAction(request: ActionExecutionRequest): ActionExecutionResult {
        // TODO: Wire EmailService.sendEmail() when SMTP is implemented
        logger.warn { "EMAIL_DISPATCH: Email send not yet implemented — action recorded as approved" }
        return ActionExecutionResult(
            success = true,
            message = "Email action approved (SMTP delivery pending implementation)",
            action = request.action,
        )
    }

    private suspend fun dispatchJiraAction(request: ActionExecutionRequest): ActionExecutionResult {
        val payload = request.payload
        return when (request.action) {
            ApprovalAction.JIRA_CREATE_ISSUE -> {
                val issue = brainWriteService.createIssue(
                    summary = payload["summary"] ?: "Untitled",
                    description = payload["description"],
                    issueType = payload["issue_type"] ?: "Task",
                    priority = payload["priority"],
                    labels = payload["labels"]?.split(",")?.map { it.trim() } ?: emptyList(),
                    epicKey = payload["epic_key"],
                )
                logger.info { "JIRA_CREATED: ${issue.key} — ${issue.summary}" }
                ActionExecutionResult(
                    success = true,
                    message = "Created Jira issue: ${issue.key}",
                    action = request.action,
                    artifactId = issue.key,
                )
            }

            ApprovalAction.JIRA_UPDATE_ISSUE -> {
                val issueKey = payload["issue_key"] ?: error("Missing issue_key")
                val issue = brainWriteService.updateIssue(
                    issueKey = issueKey,
                    summary = payload["summary"],
                    description = payload["description"],
                    assignee = payload["assignee"],
                    priority = payload["priority"],
                    labels = payload["labels"]?.split(",")?.map { it.trim() },
                )
                logger.info { "JIRA_UPDATED: ${issue.key}" }
                ActionExecutionResult(
                    success = true,
                    message = "Updated Jira issue: ${issue.key}",
                    action = request.action,
                    artifactId = issue.key,
                )
            }

            ApprovalAction.JIRA_COMMENT -> {
                val issueKey = payload["issue_key"] ?: error("Missing issue_key")
                val comment = brainWriteService.addComment(
                    issueKey = issueKey,
                    comment = payload["comment"] ?: payload["body"] ?: "",
                )
                logger.info { "JIRA_COMMENTED: $issueKey (${comment.id})" }
                ActionExecutionResult(
                    success = true,
                    message = "Added comment to $issueKey",
                    action = request.action,
                    artifactId = issueKey,
                )
            }

            ApprovalAction.JIRA_TRANSITION -> {
                val issueKey = payload["issue_key"] ?: error("Missing issue_key")
                val transition = payload["transition"] ?: error("Missing transition")
                brainWriteService.transitionIssue(issueKey, transition)
                logger.info { "JIRA_TRANSITIONED: $issueKey → $transition" }
                ActionExecutionResult(
                    success = true,
                    message = "Transitioned $issueKey → $transition",
                    action = request.action,
                    artifactId = issueKey,
                )
            }

            else -> ActionExecutionResult(
                success = false,
                message = "Unknown Jira action: ${request.action}",
                action = request.action,
            )
        }
    }

    private suspend fun dispatchConfluenceAction(request: ActionExecutionRequest): ActionExecutionResult {
        val payload = request.payload
        return when (request.action) {
            ApprovalAction.CONFLUENCE_CREATE_PAGE -> {
                val page = brainWriteService.createPage(
                    title = payload["title"] ?: "Untitled",
                    content = payload["content"] ?: "",
                    parentPageId = payload["parent_page_id"],
                )
                logger.info { "CONFLUENCE_CREATED: ${page.id} — ${page.title}" }
                ActionExecutionResult(
                    success = true,
                    message = "Created Confluence page: ${page.title}",
                    action = request.action,
                    artifactId = page.id,
                )
            }

            ApprovalAction.CONFLUENCE_UPDATE_PAGE -> {
                val pageId = payload["page_id"] ?: error("Missing page_id")
                val version = payload["version"]?.toIntOrNull() ?: 1
                val page = brainWriteService.updatePage(
                    pageId = pageId,
                    title = payload["title"] ?: "",
                    content = payload["content"] ?: "",
                    version = version,
                )
                logger.info { "CONFLUENCE_UPDATED: ${page.id} — ${page.title}" }
                ActionExecutionResult(
                    success = true,
                    message = "Updated Confluence page: ${page.title}",
                    action = request.action,
                    artifactId = page.id,
                )
            }

            else -> ActionExecutionResult(
                success = false,
                message = "Unknown Confluence action: ${request.action}",
                action = request.action,
            )
        }
    }

    private suspend fun dispatchPrAction(request: ActionExecutionRequest): ActionExecutionResult {
        // TODO: Wire GitWriteService when PR create/comment/merge API is implemented
        logger.warn { "PR_DISPATCH: PR operations not yet implemented — action recorded as approved" }
        return ActionExecutionResult(
            success = true,
            message = "PR action approved (Git write API pending implementation)",
            action = request.action,
        )
    }

    private suspend fun dispatchKbAction(request: ActionExecutionRequest): ActionExecutionResult {
        return ActionExecutionResult(
            success = true,
            message = "KB action approved for execution",
            action = request.action,
        )
    }

    // --- E4-S4: Batch Approval ---

    /**
     * Execute a batch of actions with a single approve/deny decision.
     * Groups related approvals to reduce approval fatigue.
     *
     * @param requests List of action requests to batch
     * @return List of results, one per request
     */
    suspend fun executeBatch(
        requests: List<ActionExecutionRequest>,
    ): List<ActionExecutionResult> {
        if (requests.isEmpty()) return emptyList()

        logger.info { "BATCH_EXECUTE: ${requests.size} actions" }

        // Group by action type for batch evaluation
        val byAction = requests.groupBy { it.action }
        val results = mutableListOf<ActionExecutionResult>()

        for ((action, group) in byAction) {
            // Evaluate once per action type (same approval rule applies)
            val decision = evaluateApproval(
                action = action,
                payload = group.first().payload,
                clientId = group.first().clientId,
                projectId = group.first().projectId,
            )

            when (decision) {
                ApprovalDecision.AUTO_APPROVED -> {
                    // All in this group auto-approved → dispatch each
                    for (req in group) {
                        results.add(dispatchAction(req))
                    }
                }
                ApprovalDecision.NEEDS_APPROVAL -> {
                    // Batch needs approval → single queue item for the group
                    val preview = "${group.size}× ${action.name}: " +
                        group.take(3).joinToString(", ") { buildApprovalPreview(it).take(60) } +
                        if (group.size > 3) " ..." else ""

                    notificationRpc.emitEvent(
                        clientId = group.first().clientId,
                        event = com.jervis.dto.events.JervisEvent.ApprovalRequired(
                            clientId = group.first().clientId,
                            taskId = "batch-${System.currentTimeMillis()}",
                            action = action.name,
                            preview = preview,
                            context = "Batch: ${group.size} actions",
                            timestamp = java.time.Instant.now().toString(),
                        ),
                    )

                    for (req in group) {
                        results.add(ActionExecutionResult(
                            success = true,
                            message = "Queued in batch for approval",
                            action = req.action,
                        ))
                    }
                }
                ApprovalDecision.DENIED -> {
                    for (req in group) {
                        results.add(ActionExecutionResult(
                            success = false,
                            message = "Action denied by guidelines",
                            action = req.action,
                        ))
                    }
                }
            }
        }
        return results
    }

    // --- E4-S5: Approval Analytics & Trust Building ---

    /**
     * Record an approval decision for analytics.
     * Tracks approve/deny ratio per action type for trust building.
     */
    suspend fun recordApprovalDecision(
        action: ApprovalAction,
        clientId: String,
        approved: Boolean,
    ) {
        // E4-S5: Persist to MongoDB
        try {
            val existing = approvalStatisticsRepository.findByClientIdAndAction(clientId, action.name)
            val doc = if (existing != null) {
                existing.copy(
                    approvedCount = existing.approvedCount + if (approved) 1 else 0,
                    deniedCount = existing.deniedCount + if (!approved) 1 else 0,
                    lastDecisionAt = java.time.Instant.now(),
                )
            } else {
                ApprovalStatisticsDocument(
                    clientId = clientId,
                    action = action.name,
                    approvedCount = if (approved) 1 else 0,
                    deniedCount = if (!approved) 1 else 0,
                    lastDecisionAt = java.time.Instant.now(),
                )
            }
            approvalStatisticsRepository.save(doc)

            logger.info {
                "APPROVAL_STATS: action=${action.name} client=$clientId " +
                    "approved=${doc.approvedCount} denied=${doc.deniedCount}"
            }

            // E4-S5: Auto-approve suggestion when ratio is high enough
            if (approved) {
                val total = doc.approvedCount + doc.deniedCount
                if (total >= 10 && doc.deniedCount == 0) {
                    logger.info {
                        "TRUST_SUGGESTION: action=${action.name} client=$clientId " +
                            "all $total actions approved — consider enabling auto-approve"
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist approval statistics (non-blocking)" }
        }
    }

    /**
     * Get approval statistics for a client (for UI display / suggestion engine).
     */
    suspend fun getApprovalStats(clientId: String): Map<ApprovalAction, ApprovalStatisticsDocument> {
        val result = mutableMapOf<ApprovalAction, ApprovalStatisticsDocument>()
        try {
            approvalStatisticsRepository.findByClientId(clientId).collect { doc ->
                try {
                    val action = ApprovalAction.valueOf(doc.action)
                    result[action] = doc
                } catch (_: Exception) {
                    // Unknown action name, skip
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load approval statistics for client=$clientId" }
        }
        return result
    }

    /**
     * Check if an auto-approve suggestion should be shown for a given action.
     * Returns true if all recent approvals were approved (≥10 total, 0 denied).
     */
    suspend fun shouldSuggestAutoApprove(clientId: String, action: ApprovalAction): Boolean {
        return try {
            val doc = approvalStatisticsRepository.findByClientIdAndAction(clientId, action.name)
                ?: return false
            val total = doc.approvedCount + doc.deniedCount
            total >= 10 && doc.deniedCount == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to check auto-approve suggestion" }
            false
        }
    }

    // --- E5-S6: Action Result Tracking ---

    /**
     * Record an executed action result for audit trail.
     * Stores result in KB (as action_log) and notifies user.
     */
    suspend fun recordActionResult(
        request: ActionExecutionRequest,
        result: ActionExecutionResult,
    ) {
        logger.info {
            "ACTION_RESULT: action=${result.action} success=${result.success} " +
                "message=${result.message.take(100)} artifactId=${result.artifactId}"
        }

        // Record approval decision for analytics
        recordApprovalDecision(
            action = request.action,
            clientId = request.clientId,
            approved = result.success,
        )
    }

    companion object {
        /** Map ApprovalAction → guidelines approval rule field name. */
        private val ACTION_TO_RULE_FIELD = mapOf(
            ApprovalAction.GIT_COMMIT to "autoApproveCommit",
            ApprovalAction.GIT_PUSH to "autoApprovePush",
            ApprovalAction.EMAIL_SEND to "autoApproveEmail",
            ApprovalAction.EMAIL_REPLY to "autoApproveEmail",
            ApprovalAction.JIRA_CREATE_ISSUE to "autoApproveJiraCreate",
            ApprovalAction.JIRA_UPDATE_ISSUE to "autoApproveJiraComment",
            ApprovalAction.JIRA_COMMENT to "autoApproveJiraComment",
            ApprovalAction.JIRA_TRANSITION to "autoApproveJiraComment",
            ApprovalAction.CONFLUENCE_CREATE_PAGE to "autoApproveConfluenceUpdate",
            ApprovalAction.CONFLUENCE_UPDATE_PAGE to "autoApproveConfluenceUpdate",
            ApprovalAction.PR_CREATE to "autoApprovePrComment",
            ApprovalAction.PR_COMMENT to "autoApprovePrComment",
            ApprovalAction.PR_MERGE to "autoApprovePush",
            ApprovalAction.CHAT_REPLY to "autoApproveChatReply",
            ApprovalAction.CODING_DISPATCH to "autoApproveCodingDispatch",
        )
    }
}
