package com.jervis.common.dto

data class CodingExecuteRequest(
    val correlationId: String,
    val clientId: String,
    val projectId: String? = null,
    val taskDescription: String,
    val targetFiles: List<String> = emptyList(),
    val codingInstruction: String,
    val codingRules: String,
)

data class CodingExecuteResponse(
    val success: Boolean,
    val summary: String,
)

/**
 * Top-level request sent to the Coding service/tool.
 *
 * IMPORTANT: This is NOT meant to be called directly by an LLM planner.
 * It is populated by the GOAP agent inside an action and then sent to the selected provider.
 */
data class CodingServiceRequest(
    val envelope: CodingTaskEnvelope,
)

/** Top-level response received from the Coding service/tool. */
data class CodingServiceResponse(
    val result: CodingTaskResult,
)

data class CodingTaskEnvelope(
    val run: RunInfo,
    val repo: RepoInfo,
    val codingStyle: CodingStyleProfile,
    val context: TaskContext,
    val workingSet: WorkingSet,
    val changeRequest: ChangeRequest,
    val acceptance: Acceptance,
    val responseContract: ResponseContract =
        ResponseContract(
            mustReturnJsonOnly = true,
            outputFormat = CodingOutputFormat.PATCH_OR_QUESTIONS,
        ),
)

data class ProtocolInfo(
    val name: String,
    val version: String,
)

data class RunInfo(
    /** Stable id for traceability (e.g., timestamp + ticket). */
    val runId: String,
    /** Optional ticket id (Jira, YouTrack, etc.). */
    val taskId: String? = null,
    val title: String,
    /** High-level intent: e.g., apply_changes | propose_patch | create_files | refactor. */
    val intent: String,
    /** Optional revision number for clarification loop (1,2,3...). */
    val revision: Int = 1,
)

data class RepoInfo(
    val vcs: String = "git",
    /** Repository root relative to tool workspace. */
    val root: String = ".",
    /** Base ref/commit/branch for reproducibility. */
    val baseRef: String = "HEAD",
    /** Optional language preference used when multiple options exist. */
    val languagePolicy: String? = null,
)

/**
 * Coding style and project rules.
 * This must be provided by GOAP agent because each project differs.
 */
data class CodingStyleProfile(
    val profileName: String,
    val languages: LanguageRules,
    val comments: CommentRules = CommentRules(),
    val formatting: FormattingRules = FormattingRules(),
    val architectureRules: List<String> = emptyList(),
    val testingRules: List<String> = emptyList(),
)

data class LanguageRules(
    /** Allowed languages for edits. */
    val allowed: List<String>,
    /** Rule for new code: e.g., KOTLIN_ONLY | JAVA_ONLY | FOLLOW_EXISTING_MODULE. */
    val newCodeRule: String,
)

data class CommentRules(
    /** e.g., INLINE_REQUIRED_FOR_NON_OBVIOUS_LOGIC | INLINE_FOR_COMPLEX_ONLY | NO_INLINE. */
    val inlinePolicy: String = "INLINE_FOR_COMPLEX_ONLY",
    /** e.g., KDOC_FOR_PUBLIC_APIS_ONLY | KDOC_REQUIRED | NO_KDOC. */
    val kdocPolicy: String = "KDOC_FOR_PUBLIC_APIS_ONLY",
)

data class FormattingRules(
    val tools: List<String> = emptyList(),
    val maxLineLength: Int? = null,
)

/** Additional narrative context (already distilled by GOAP agent). */
data class TaskContext(
    val problemSummary: List<String>,
    val decisions: List<String> = emptyList(),
    val nonGoals: List<String> = emptyList(),
    val references: List<ReferenceLink> = emptyList(),
)

data class ReferenceLink(
    /** e.g., jira | confluence | meeting | web | rag | graphdb */
    val kind: String,
    val id: String? = null,
    val title: String? = null,
    val url: String? = null,
    val note: String? = null,
)

/**
 * The complete code context that the coding agent should rely on.
 * Provide full file content whenever possible to avoid “investigation” work by the coding agent.
 */
data class WorkingSet(
    val files: List<WorkingFile>,
    val searchHints: List<SearchHint> = emptyList(),
)

data class WorkingFile(
    val path: String,
    /** Full file content snapshot (preferred). */
    val content: String,
    /** Optional hash for integrity / drift detection. */
    val contentSha256: String? = null,
    /** e.g., primary | reference | test | config */
    val role: String? = null,
)

data class SearchHint(
    val path: String,
    val needle: String,
    val note: String? = null,
)

/**
 * Deterministic, execution-ready request.
 * Do not include “locate”, “investigate”, “figure out” steps — those belong to GOAP/RAG agents.
 */
data class ChangeRequest(
    val targetBehavior: List<String>,
    val constraints: ChangeConstraints = ChangeConstraints(),
    val implementationPlan: ImplementationPlan,
)

data class ChangeConstraints(
    val doNotAddDependencies: Boolean = true,
    val limitFilesChanged: Int? = null,
    val allowedPaths: List<String> = emptyList(),
    val doNotRefactorUnrelated: Boolean = true,
)

data class ImplementationPlan(
    val steps: List<PlanStep>,
)

data class PlanStep(
    val id: String,
    /** e.g., code_change | tests | docs | config */
    val type: String,
    /** Concrete instruction; must be implementable without additional discovery. */
    val instruction: String,
    /** Files relevant for this step (can be empty if obvious). */
    val filePaths: List<String> = emptyList(),
)

/** Acceptance criteria and verification guidance. */
data class Acceptance(
    val criteria: List<String>,
    val howToVerify: Verification = Verification(),
)

data class Verification(
    val commands: List<String> = emptyList(),
    val manualChecks: List<String> = emptyList(),
)

/**
 * Forces structured behavior on the coding agent output.
 * The coding agent must return JSON-only with either a patch OR questions.
 */
data class ResponseContract(
    val mustReturnJsonOnly: Boolean,
    val outputFormat: CodingOutputFormat,
)

enum class CodingOutputFormat {
    PATCH_OR_QUESTIONS,
}

data class CodingTaskResult(
    val runId: String,
    val status: CodingResultStatus,
    val summary: List<String> = emptyList(),
    val patch: ProposedPatch? = null,
    val filesChanged: List<ChangedFile> = emptyList(),
    val questions: List<CodingQuestion> = emptyList(),
    val verification: VerificationResult? = null,
    val error: CodingError? = null,
)

enum class CodingResultStatus {
    PATCH_PROPOSED,
    NEEDS_CLARIFICATION,
    FAILED,
}

data class ProposedPatch(
    val format: String,
    val text: String,
)

data class ChangedFile(
    val path: String,
    /** Optional full new content if the executor prefers overwrite over patch apply. */
    val newContent: String? = null,
)

data class CodingQuestion(
    val id: String,
    /** missing_context | ambiguity | permissions | environment | test_failure */
    val type: String,
    val question: String,
    val requestedArtifacts: List<RequestedArtifact> = emptyList(),
)

data class RequestedArtifact(
    /** file | search | snippet | command_output */
    val kind: String,
    val pathHint: String? = null,
    val query: String? = null,
    val note: String? = null,
)

data class VerificationResult(
    val commandsToRun: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

data class CodingError(
    val message: String,
    val details: String? = null,
)
