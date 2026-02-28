package com.jervis.dto.guidelines

import kotlinx.serialization.Serializable

/**
 * Scope at which guidelines apply. Lower scopes override/extend higher ones.
 * Resolution order: PROJECT overrides CLIENT, CLIENT overrides GLOBAL (deep merge).
 */
@Serializable
enum class GuidelinesScope {
    GLOBAL,
    CLIENT,
    PROJECT,
}

/**
 * Category of guidelines. Each category has its own typed rule structure.
 */
@Serializable
enum class GuidelinesCategory {
    CODING,
    GIT,
    REVIEW,
    COMMUNICATION,
    APPROVAL,
    GENERAL,
}

/**
 * Coding guidelines — forbidden/required patterns, naming, limits.
 */
@Serializable
data class CodingGuidelinesDto(
    val forbiddenPatterns: List<PatternRule> = emptyList(),
    val requiredPatterns: List<PatternRule> = emptyList(),
    val maxFileLines: Int? = null,
    val maxFunctionLines: Int? = null,
    val namingConventions: Map<String, String> = emptyMap(),
    val languageSpecific: Map<String, LanguageRulesDto> = emptyMap(),
    val principles: List<String> = emptyList(),
)

/**
 * A single pattern rule (regex-based) with description and severity.
 */
@Serializable
data class PatternRule(
    val pattern: String,
    val description: String = "",
    val severity: PatternSeverity = PatternSeverity.WARNING,
    val fileGlob: String? = null,
)

@Serializable
enum class PatternSeverity {
    INFO,
    WARNING,
    BLOCKER,
}

/**
 * Per-language sub-rules for coding guidelines.
 */
@Serializable
data class LanguageRulesDto(
    val namingConvention: String? = null,
    val forbiddenImports: List<String> = emptyList(),
    val requiredImports: List<String> = emptyList(),
    val maxFileLines: Int? = null,
)

/**
 * Git & commit guidelines — commit format, branch naming, protected branches.
 */
@Serializable
data class GitGuidelinesDto(
    val commitMessageTemplate: String? = null,
    val commitMessageValidators: List<String> = emptyList(),
    val branchNameTemplate: String? = null,
    val requireJiraReference: Boolean = false,
    val squashOnMerge: Boolean = false,
    val protectedBranches: List<String> = emptyList(),
)

/**
 * Code review guidelines — checklists, focus areas, limits.
 */
@Serializable
data class ReviewGuidelinesDto(
    val mustHaveTests: Boolean = false,
    val mustPassLint: Boolean = false,
    val maxChangedFiles: Int? = null,
    val maxChangedLines: Int? = null,
    val forbiddenFileChanges: List<String> = emptyList(),
    val focusAreas: List<String> = emptyList(),
    val checklistItems: List<ReviewChecklistItem> = emptyList(),
    val languageReviewRules: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class ReviewChecklistItem(
    val id: String,
    val label: String,
    val severity: PatternSeverity = PatternSeverity.WARNING,
    val enabled: Boolean = true,
)

/**
 * Communication guidelines — response style, language, formats.
 */
@Serializable
data class CommunicationGuidelinesDto(
    val emailResponseLanguage: String? = null,
    val emailSignature: String? = null,
    val commentStyle: String? = null,
    val jiraCommentLanguage: String? = null,
    val formalityLevel: String? = null,
    val customRules: List<String> = emptyList(),
)

/**
 * Approval guidelines — per-action auto-approval configuration.
 */
@Serializable
data class ApprovalGuidelinesDto(
    val autoApproveCommit: ApprovalRule = ApprovalRule(),
    val autoApprovePush: ApprovalRule = ApprovalRule(),
    val autoApproveEmail: ApprovalRule = ApprovalRule(),
    val autoApproveJiraComment: ApprovalRule = ApprovalRule(),
    val autoApproveJiraCreate: ApprovalRule = ApprovalRule(),
    val autoApprovePrComment: ApprovalRule = ApprovalRule(),
    val autoApproveChatReply: ApprovalRule = ApprovalRule(),
    val autoApproveConfluenceUpdate: ApprovalRule = ApprovalRule(),
    val autoApproveCodingDispatch: ApprovalRule = ApprovalRule(),
)

/**
 * A single approval rule with conditions.
 */
@Serializable
data class ApprovalRule(
    val enabled: Boolean = false,
    val whenRiskLevelBelow: String? = null,
    val whenConfidenceAbove: Double? = null,
)

/**
 * General/misc guidelines — catch-all for uncategorized rules.
 */
@Serializable
data class GeneralGuidelinesDto(
    val customRules: List<String> = emptyList(),
    val notes: String? = null,
)

/**
 * Complete guidelines document for a specific scope.
 */
@Serializable
data class GuidelinesDocumentDto(
    val id: String? = null,
    val scope: GuidelinesScope = GuidelinesScope.GLOBAL,
    val clientId: String? = null,
    val projectId: String? = null,
    val coding: CodingGuidelinesDto = CodingGuidelinesDto(),
    val git: GitGuidelinesDto = GitGuidelinesDto(),
    val review: ReviewGuidelinesDto = ReviewGuidelinesDto(),
    val communication: CommunicationGuidelinesDto = CommunicationGuidelinesDto(),
    val approval: ApprovalGuidelinesDto = ApprovalGuidelinesDto(),
    val general: GeneralGuidelinesDto = GeneralGuidelinesDto(),
)

/**
 * Request to update guidelines for a specific scope + category.
 * Only non-null fields are applied (partial update).
 */
@Serializable
data class GuidelinesUpdateRequest(
    val scope: GuidelinesScope,
    val clientId: String? = null,
    val projectId: String? = null,
    val coding: CodingGuidelinesDto? = null,
    val git: GitGuidelinesDto? = null,
    val review: ReviewGuidelinesDto? = null,
    val communication: CommunicationGuidelinesDto? = null,
    val approval: ApprovalGuidelinesDto? = null,
    val general: GeneralGuidelinesDto? = null,
)

/**
 * Merged (resolved) guidelines for a specific client+project context.
 * Result of GLOBAL → CLIENT → PROJECT deep merge.
 * Includes metadata about which scope each value came from.
 */
@Serializable
data class MergedGuidelinesDto(
    val coding: CodingGuidelinesDto = CodingGuidelinesDto(),
    val git: GitGuidelinesDto = GitGuidelinesDto(),
    val review: ReviewGuidelinesDto = ReviewGuidelinesDto(),
    val communication: CommunicationGuidelinesDto = CommunicationGuidelinesDto(),
    val approval: ApprovalGuidelinesDto = ApprovalGuidelinesDto(),
    val general: GeneralGuidelinesDto = GeneralGuidelinesDto(),
    val effectiveScopes: Map<String, GuidelinesScope> = emptyMap(),
)
