package com.jervis.entity

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.guidelines.ApprovalGuidelinesDto
import com.jervis.dto.guidelines.ApprovalRule
import com.jervis.dto.guidelines.CodingGuidelinesDto
import com.jervis.dto.guidelines.CommunicationGuidelinesDto
import com.jervis.dto.guidelines.GeneralGuidelinesDto
import com.jervis.dto.guidelines.GitGuidelinesDto
import com.jervis.dto.guidelines.GuidelinesDocumentDto
import com.jervis.dto.guidelines.GuidelinesScope
import com.jervis.dto.guidelines.MergedGuidelinesDto
import com.jervis.dto.guidelines.ReviewGuidelinesDto
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document storing guidelines for a specific scope.
 *
 * Scope resolution:
 * - GLOBAL: clientId=null, projectId=null
 * - CLIENT: clientId=set, projectId=null
 * - PROJECT: clientId=set, projectId=set
 *
 * Each scope stores all categories. Merge logic applies GLOBAL → CLIENT → PROJECT.
 */
@Document(collection = "guidelines")
@CompoundIndex(name = "idx_scope", def = "{'clientId': 1, 'projectId': 1}", unique = true)
data class GuidelinesDocument(
    @Id
    val id: String = generateId(),
    val clientId: ClientId? = null,
    val projectId: ProjectId? = null,
    val coding: CodingGuidelinesDto = CodingGuidelinesDto(),
    val git: GitGuidelinesDto = GitGuidelinesDto(),
    val review: ReviewGuidelinesDto = ReviewGuidelinesDto(),
    val communication: CommunicationGuidelinesDto = CommunicationGuidelinesDto(),
    val approval: ApprovalGuidelinesDto = ApprovalGuidelinesDto(),
    val general: GeneralGuidelinesDto = GeneralGuidelinesDto(),
    val updatedAt: Instant = Instant.now(),
) {
    val scope: GuidelinesScope
        get() = when {
            clientId == null && projectId == null -> GuidelinesScope.GLOBAL
            projectId == null -> GuidelinesScope.CLIENT
            else -> GuidelinesScope.PROJECT
        }

    fun toDto(): GuidelinesDocumentDto = GuidelinesDocumentDto(
        id = id,
        scope = scope,
        clientId = clientId?.toString(),
        projectId = projectId?.toString(),
        coding = coding,
        git = git,
        review = review,
        communication = communication,
        approval = approval,
        general = general,
    )

    companion object {
        fun generateId(): String = org.bson.types.ObjectId().toHexString()

        /**
         * Create default global guidelines with sensible defaults.
         */
        fun defaultGlobal(): GuidelinesDocument = GuidelinesDocument(
            coding = CodingGuidelinesDto(
                maxFileLines = 500,
                maxFunctionLines = 30,
                namingConventions = mapOf(
                    "kotlin" to "camelCase",
                    "python" to "snake_case",
                ),
                principles = listOf(
                    "Idiomatic Kotlin — NEVER Java-style code in Kotlin. Use data classes, sealed classes, extension functions, scope functions (let, run, apply, also), destructuring, sequences.",
                    "Prefer expression bodies over block bodies where the expression fits on one line.",
                    "Prefer val over var. Prefer immutable collections (List, Map, Set) over mutable.",
                    "Use Kotlin stdlib — filterNotNull, mapNotNull, groupBy, associate, partition, zip — instead of manual loops.",
                    "SOLID: Single Responsibility — every class/function does ONE thing. Max 500 lines per file, max 30 lines per function.",
                    "SOLID: Open/Closed — extend via interfaces/sealed classes, not by modifying existing code.",
                    "SOLID: Liskov Substitution — subtypes must be substitutable for base types without surprises.",
                    "SOLID: Interface Segregation — small, focused interfaces. No god-interfaces.",
                    "SOLID: Dependency Inversion — depend on abstractions (interfaces), inject via constructor.",
                    "IF-LESS programming — prefer polymorphism, sealed class + when (exhaustive), strategy pattern, map lookups over if/else chains.",
                    "Replace boolean flags with sealed class/enum variants.",
                    "Replace nested if/else with early returns (guard clauses) or when expressions.",
                    "Never use if/else for type dispatch — use sealed class + when or visitor pattern.",
                    "Self-descriptive code — NO inline comments inside function bodies. If code needs a comment, rename the variable/function to be self-explanatory.",
                    "KDoc only on public API — classes, public functions, interfaces. Describe WHAT and WHY, never HOW.",
                    "No TODO comments in committed code — create a task/issue instead.",
                    "Functions: max 3 parameters. More → use data class as parameter object.",
                    "No magic numbers/strings — use named constants or enums.",
                    "Fail fast — validate at boundaries, throw early, never silently swallow errors.",
                    "Prefer composition over inheritance.",
                    "DRY but not premature — extract only when pattern repeats 3+ times.",
                ),
            ),
            git = GitGuidelinesDto(
                commitMessageTemplate = "feat|fix|refactor|docs|test|chore(scope): description",
            ),
            review = ReviewGuidelinesDto(
                forbiddenFileChanges = listOf(".env", "secrets/*", "*.pem", "*.key"),
            ),
            approval = ApprovalGuidelinesDto(
                autoApproveCommit = ApprovalRule(enabled = false),
                autoApprovePush = ApprovalRule(enabled = false),
            ),
        )
    }
}

/**
 * Merge multiple GuidelinesDocuments in scope order (GLOBAL → CLIENT → PROJECT).
 * Later scopes override earlier ones. Lists are concatenated, non-null scalars override.
 */
fun mergeGuidelines(documents: List<GuidelinesDocument>): MergedGuidelinesDto {
    if (documents.isEmpty()) {
        val default = GuidelinesDocument.defaultGlobal()
        return MergedGuidelinesDto(
            coding = default.coding,
            git = default.git,
            review = default.review,
            communication = default.communication,
            approval = default.approval,
            general = default.general,
        )
    }

    val sorted = documents.sortedBy { it.scope.ordinal }
    val effectiveScopes = mutableMapOf<String, GuidelinesScope>()

    var coding = CodingGuidelinesDto()
    var git = GitGuidelinesDto()
    var review = ReviewGuidelinesDto()
    var communication = CommunicationGuidelinesDto()
    var approval = ApprovalGuidelinesDto()
    var general = GeneralGuidelinesDto()

    for (doc in sorted) {
        coding = mergeCoding(coding, doc.coding)
        git = mergeGit(git, doc.git)
        review = mergeReview(review, doc.review)
        communication = mergeCommunication(communication, doc.communication)
        approval = mergeApproval(approval, doc.approval)
        general = mergeGeneral(general, doc.general)

        trackScopes(effectiveScopes, doc)
    }

    return MergedGuidelinesDto(
        coding = coding,
        git = git,
        review = review,
        communication = communication,
        approval = approval,
        general = general,
        effectiveScopes = effectiveScopes,
    )
}

private fun mergeCoding(base: CodingGuidelinesDto, override: CodingGuidelinesDto): CodingGuidelinesDto =
    CodingGuidelinesDto(
        forbiddenPatterns = base.forbiddenPatterns + override.forbiddenPatterns,
        requiredPatterns = base.requiredPatterns + override.requiredPatterns,
        maxFileLines = override.maxFileLines ?: base.maxFileLines,
        maxFunctionLines = override.maxFunctionLines ?: base.maxFunctionLines,
        namingConventions = base.namingConventions + override.namingConventions,
        languageSpecific = base.languageSpecific + override.languageSpecific,
        principles = base.principles + override.principles,
    )

private fun mergeGit(base: GitGuidelinesDto, override: GitGuidelinesDto): GitGuidelinesDto =
    GitGuidelinesDto(
        commitMessageTemplate = override.commitMessageTemplate ?: base.commitMessageTemplate,
        commitMessageValidators = base.commitMessageValidators + override.commitMessageValidators,
        branchNameTemplate = override.branchNameTemplate ?: base.branchNameTemplate,
        requireJiraReference = override.requireJiraReference || base.requireJiraReference,
        squashOnMerge = override.squashOnMerge || base.squashOnMerge,
        protectedBranches = (base.protectedBranches + override.protectedBranches).distinct(),
    )

private fun mergeReview(base: ReviewGuidelinesDto, override: ReviewGuidelinesDto): ReviewGuidelinesDto =
    ReviewGuidelinesDto(
        mustHaveTests = override.mustHaveTests || base.mustHaveTests,
        mustPassLint = override.mustPassLint || base.mustPassLint,
        maxChangedFiles = override.maxChangedFiles ?: base.maxChangedFiles,
        maxChangedLines = override.maxChangedLines ?: base.maxChangedLines,
        forbiddenFileChanges = (base.forbiddenFileChanges + override.forbiddenFileChanges).distinct(),
        focusAreas = (base.focusAreas + override.focusAreas).distinct(),
        checklistItems = base.checklistItems + override.checklistItems,
        languageReviewRules = base.languageReviewRules + override.languageReviewRules,
    )

private fun mergeCommunication(
    base: CommunicationGuidelinesDto,
    override: CommunicationGuidelinesDto,
): CommunicationGuidelinesDto =
    CommunicationGuidelinesDto(
        emailResponseLanguage = override.emailResponseLanguage ?: base.emailResponseLanguage,
        emailSignature = override.emailSignature ?: base.emailSignature,
        commentStyle = override.commentStyle ?: base.commentStyle,
        jiraCommentLanguage = override.jiraCommentLanguage ?: base.jiraCommentLanguage,
        formalityLevel = override.formalityLevel ?: base.formalityLevel,
        customRules = base.customRules + override.customRules,
    )

private fun mergeApproval(base: ApprovalGuidelinesDto, override: ApprovalGuidelinesDto): ApprovalGuidelinesDto =
    ApprovalGuidelinesDto(
        autoApproveCommit = mergeApprovalRule(base.autoApproveCommit, override.autoApproveCommit),
        autoApprovePush = mergeApprovalRule(base.autoApprovePush, override.autoApprovePush),
        autoApproveEmail = mergeApprovalRule(base.autoApproveEmail, override.autoApproveEmail),
        autoApproveJiraComment = mergeApprovalRule(base.autoApproveJiraComment, override.autoApproveJiraComment),
        autoApproveJiraCreate = mergeApprovalRule(base.autoApproveJiraCreate, override.autoApproveJiraCreate),
        autoApprovePrComment = mergeApprovalRule(base.autoApprovePrComment, override.autoApprovePrComment),
        autoApproveChatReply = mergeApprovalRule(base.autoApproveChatReply, override.autoApproveChatReply),
        autoApproveConfluenceUpdate = mergeApprovalRule(
            base.autoApproveConfluenceUpdate,
            override.autoApproveConfluenceUpdate,
        ),
        autoApproveCodingDispatch = mergeApprovalRule(
            base.autoApproveCodingDispatch,
            override.autoApproveCodingDispatch,
        ),
    )

private fun mergeApprovalRule(base: ApprovalRule, override: ApprovalRule): ApprovalRule =
    if (override.enabled || override.whenRiskLevelBelow != null || override.whenConfidenceAbove != null) {
        override
    } else {
        base
    }

private fun mergeGeneral(base: GeneralGuidelinesDto, override: GeneralGuidelinesDto): GeneralGuidelinesDto =
    GeneralGuidelinesDto(
        customRules = base.customRules + override.customRules,
        notes = override.notes ?: base.notes,
    )

private fun trackScopes(scopes: MutableMap<String, GuidelinesScope>, doc: GuidelinesDocument) {
    val s = doc.scope
    if (doc.coding != CodingGuidelinesDto()) scopes["coding"] = s
    if (doc.git != GitGuidelinesDto()) scopes["git"] = s
    if (doc.review != ReviewGuidelinesDto()) scopes["review"] = s
    if (doc.communication != CommunicationGuidelinesDto()) scopes["communication"] = s
    if (doc.approval != ApprovalGuidelinesDto()) scopes["approval"] = s
    if (doc.general != GeneralGuidelinesDto()) scopes["general"] = s
}
