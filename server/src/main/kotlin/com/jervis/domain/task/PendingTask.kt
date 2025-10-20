package com.jervis.domain.task

import org.bson.types.ObjectId
import java.time.Instant

data class PendingTask(
    val id: ObjectId = ObjectId(),
    val taskType: PendingTaskType,
    val severity: PendingTaskSeverity,
    val priority: PendingTaskPriority = PendingTaskPriority.NORMAL,
    val title: String,
    val description: String,
    val context: Map<String, String>,
    val errorDetails: String?,
    val autoFixAttempted: Boolean = false,
    val autoFixResult: String? = null,
    val status: PendingTaskStatus = PendingTaskStatus.OPEN,
    val analysisResult: String? = null,
    val suggestedSolution: String? = null,
    val projectId: ObjectId? = null,
    val clientId: ObjectId? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val nextRetryAt: Instant? = null,
    val diagnosticData: DiagnosticData? = null,
    val userInteractions: List<UserInteraction> = emptyList(),
    val configurationChanges: List<ConfigurationChange> = emptyList(),
    val relatedDocuments: List<String> = emptyList(),
    val vectorSearchQueries: List<String> = emptyList(),
    val workItemMetadata: WorkItemMetadata? = null,
    val estimatedProcessingTime: Long? = null,
    val tags: List<String> = emptyList(),
    val dependencies: List<ObjectId> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val resolvedAt: Instant? = null,
    val scheduledFor: Instant? = null,
)

data class WorkItemMetadata(
    val sourceSystem: String,
    val externalId: String?,
    val externalUrl: String?,
    val author: String?,
    val assignee: String?,
    val originalContent: String?,
    val attachments: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val dueDate: Instant? = null,
)

data class DiagnosticData(
    val systemState: Map<String, String>,
    val relevantLogs: List<String>,
    val configurationSnapshot: Map<String, String>,
    val environmentVariables: Map<String, String>,
    val dependencyVersions: Map<String, String>,
    val collectedAt: Instant = Instant.now(),
)

data class UserInteraction(
    val interactionId: String,
    val userMessage: String,
    val systemResponse: String,
    val actionTaken: String?,
    val timestamp: Instant = Instant.now(),
)

data class ConfigurationChange(
    val changeId: String,
    val configKey: String,
    val oldValue: String?,
    val newValue: String,
    val reason: String,
    val appliedBy: String,
    val appliedAt: Instant = Instant.now(),
    val verified: Boolean = false,
)

enum class PendingTaskType {
    GIT_REPOSITORY_MISSING,
    GIT_CLONE_FAILED,
    CONFIGURATION_ERROR,
    INDEXING_FAILED,
    CREDENTIAL_MISSING,
    DISK_SPACE_LOW,
    NETWORK_ERROR,
    UNKNOWN_ERROR,
    EMAIL_PROCESSING,
    SLACK_MESSAGE_ANALYSIS,
    JIRA_TASK_REVIEW,
    JIRA_BUG_ANALYSIS,
    CODE_REVIEW_PREP,
    DOCUMENTATION_GAP,
    DOCUMENTATION_REVIEW,
    API_DEPRECATION_CHECK,
    DEPENDENCY_UPDATE_CHECK,
    SECURITY_ADVISORY_REVIEW,
    PERFORMANCE_ANALYSIS,
    TEST_COVERAGE_ANALYSIS,
    REFACTORING_OPPORTUNITY,
    TECHNICAL_DEBT_ITEM,
}

enum class PendingTaskPriority {
    URGENT,
    HIGH,
    NORMAL,
    LOW,
    BACKLOG,
}

enum class PendingTaskSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO,
}

enum class PendingTaskStatus {
    OPEN,
    ANALYZING,
    ANALYZED,
    AWAITING_USER_INPUT,
    RESOLVED,
    IGNORED,
}
