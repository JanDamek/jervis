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
        // Email dispatch would integrate with EmailService
        // For now, records approval — orchestrator handles actual send
        return ActionExecutionResult(
            success = true,
            message = "Email action approved for execution",
            action = request.action,
        )
    }

    private suspend fun dispatchJiraAction(request: ActionExecutionRequest): ActionExecutionResult {
        // Jira actions route through brain_client in Python orchestrator
        return ActionExecutionResult(
            success = true,
            message = "Jira action approved for execution",
            action = request.action,
        )
    }

    private suspend fun dispatchConfluenceAction(request: ActionExecutionRequest): ActionExecutionResult {
        return ActionExecutionResult(
            success = true,
            message = "Confluence action approved for execution",
            action = request.action,
        )
    }

    private suspend fun dispatchPrAction(request: ActionExecutionRequest): ActionExecutionResult {
        return ActionExecutionResult(
            success = true,
            message = "PR action approved for execution",
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
