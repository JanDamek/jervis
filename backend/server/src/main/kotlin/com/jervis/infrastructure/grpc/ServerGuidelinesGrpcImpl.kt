package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.contracts.server.ApprovalGuidelines as ApprovalGuidelinesProto
import com.jervis.contracts.server.ApprovalRule as ApprovalRuleProto
import com.jervis.contracts.server.CodingGuidelines as CodingGuidelinesProto
import com.jervis.contracts.server.CommunicationGuidelines as CommunicationGuidelinesProto
import com.jervis.contracts.server.GeneralGuidelines as GeneralGuidelinesProto
import com.jervis.contracts.server.GetMergedRequest
import com.jervis.contracts.server.GetRequest
import com.jervis.contracts.server.GitGuidelines as GitGuidelinesProto
import com.jervis.contracts.server.GuidelinesDocument as GuidelinesDocumentProto
import com.jervis.contracts.server.GuidelinesScope
import com.jervis.contracts.server.LanguageRules as LanguageRulesProto
import com.jervis.contracts.server.LanguageReviewRules as LanguageReviewRulesProto
import com.jervis.contracts.server.MergedGuidelines as MergedGuidelinesProto
import com.jervis.contracts.server.PatternRule as PatternRuleProto
import com.jervis.contracts.server.ReviewChecklistItem as ReviewChecklistItemProto
import com.jervis.contracts.server.ReviewGuidelines as ReviewGuidelinesProto
import com.jervis.contracts.server.ServerGuidelinesServiceGrpcKt
import com.jervis.contracts.server.SetRequest
import com.jervis.dto.guidelines.ApprovalGuidelinesDto
import com.jervis.dto.guidelines.ApprovalRule
import com.jervis.dto.guidelines.CodingGuidelinesDto
import com.jervis.dto.guidelines.CommunicationGuidelinesDto
import com.jervis.dto.guidelines.GeneralGuidelinesDto
import com.jervis.dto.guidelines.GitGuidelinesDto
import com.jervis.dto.guidelines.GuidelinesDocumentDto
import com.jervis.dto.guidelines.GuidelinesScope as DtoGuidelinesScope
import com.jervis.dto.guidelines.GuidelinesUpdateRequest
import com.jervis.dto.guidelines.LanguageRulesDto
import com.jervis.dto.guidelines.MergedGuidelinesDto
import com.jervis.dto.guidelines.PatternRule
import com.jervis.dto.guidelines.PatternSeverity
import com.jervis.dto.guidelines.ReviewChecklistItem
import com.jervis.dto.guidelines.ReviewGuidelinesDto
import com.jervis.guidelines.GuidelinesService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerGuidelinesGrpcImpl(
    private val guidelinesService: GuidelinesService,
) : ServerGuidelinesServiceGrpcKt.ServerGuidelinesServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun getMerged(request: GetMergedRequest): MergedGuidelinesProto {
        val merged = guidelinesService.getMergedGuidelines(
            clientId = request.clientId.takeIf { it.isNotBlank() }?.let { ClientId.fromString(it) },
            projectId = request.projectId.takeIf { it.isNotBlank() }?.let { ProjectId.fromString(it) },
        )
        return merged.toProto()
    }

    override suspend fun get(request: GetRequest): GuidelinesDocumentProto {
        val scope = when (request.scope) {
            GuidelinesScope.GUIDELINES_SCOPE_CLIENT -> DtoGuidelinesScope.CLIENT
            GuidelinesScope.GUIDELINES_SCOPE_PROJECT -> DtoGuidelinesScope.PROJECT
            else -> DtoGuidelinesScope.GLOBAL
        }
        val doc = guidelinesService.getGuidelines(
            scope = scope,
            clientId = request.clientId.takeIf { it.isNotBlank() }?.let { ClientId.fromString(it) },
            projectId = request.projectId.takeIf { it.isNotBlank() }?.let { ProjectId.fromString(it) },
        )
        return doc.toDto().toProto()
    }

    override suspend fun set(request: SetRequest): GuidelinesDocumentProto {
        val update = request.update.toDto()
        val doc = guidelinesService.updateGuidelines(update)
        return doc.toDto().toProto()
    }
}

// ── DTO → proto ────────────────────────────────────────────────────────

private fun GuidelinesDocumentDto.toProto(): GuidelinesDocumentProto =
    GuidelinesDocumentProto.newBuilder()
        .setId(id ?: "")
        .setScope(scope.name)
        .setClientId(clientId ?: "")
        .setProjectId(projectId ?: "")
        .setCoding(coding.toProto())
        .setGit(git.toProto())
        .setReview(review.toProto())
        .setCommunication(communication.toProto())
        .setApproval(approval.toProto())
        .setGeneral(general.toProto())
        .build()

private fun MergedGuidelinesDto.toProto(): MergedGuidelinesProto {
    val builder = MergedGuidelinesProto.newBuilder()
        .setCoding(coding.toProto())
        .setGit(git.toProto())
        .setReview(review.toProto())
        .setCommunication(communication.toProto())
        .setApproval(approval.toProto())
        .setGeneral(general.toProto())
    effectiveScopes.forEach { (k, v) -> builder.putEffectiveScopes(k, v.name) }
    return builder.build()
}

private fun CodingGuidelinesDto.toProto(): CodingGuidelinesProto {
    val builder = CodingGuidelinesProto.newBuilder()
        .addAllForbiddenPatterns(forbiddenPatterns.map { it.toProto() })
        .addAllRequiredPatterns(requiredPatterns.map { it.toProto() })
        .addAllPrinciples(principles)
    maxFileLines?.let { builder.maxFileLines = it }
    maxFunctionLines?.let { builder.maxFunctionLines = it }
    namingConventions.forEach { (k, v) -> builder.putNamingConventions(k, v) }
    languageSpecific.forEach { (k, v) -> builder.putLanguageSpecific(k, v.toProto()) }
    return builder.build()
}

private fun PatternRule.toProto(): PatternRuleProto =
    PatternRuleProto.newBuilder()
        .setPattern(pattern)
        .setDescription(description)
        .setSeverity(severity.name)
        .setFileGlob(fileGlob ?: "")
        .build()

private fun LanguageRulesDto.toProto(): LanguageRulesProto {
    val builder = LanguageRulesProto.newBuilder()
        .setNamingConvention(namingConvention ?: "")
        .addAllForbiddenImports(forbiddenImports)
        .addAllRequiredImports(requiredImports)
    maxFileLines?.let { builder.maxFileLines = it }
    return builder.build()
}

private fun GitGuidelinesDto.toProto(): GitGuidelinesProto =
    GitGuidelinesProto.newBuilder()
        .setCommitMessageTemplate(commitMessageTemplate ?: "")
        .addAllCommitMessageValidators(commitMessageValidators)
        .setBranchNameTemplate(branchNameTemplate ?: "")
        .setRequireJiraReference(requireJiraReference)
        .setSquashOnMerge(squashOnMerge)
        .addAllProtectedBranches(protectedBranches)
        .build()

private fun ReviewGuidelinesDto.toProto(): ReviewGuidelinesProto {
    val builder = ReviewGuidelinesProto.newBuilder()
        .setMustHaveTests(mustHaveTests)
        .setMustPassLint(mustPassLint)
        .addAllForbiddenFileChanges(forbiddenFileChanges)
        .addAllFocusAreas(focusAreas)
        .addAllChecklistItems(checklistItems.map { it.toProto() })
    maxChangedFiles?.let { builder.maxChangedFiles = it }
    maxChangedLines?.let { builder.maxChangedLines = it }
    languageReviewRules.forEach { (k, v) ->
        builder.putLanguageReviewRules(k, LanguageReviewRulesProto.newBuilder().addAllRules(v).build())
    }
    return builder.build()
}

private fun ReviewChecklistItem.toProto(): ReviewChecklistItemProto =
    ReviewChecklistItemProto.newBuilder()
        .setId(id)
        .setLabel(label)
        .setSeverity(severity.name)
        .setEnabled(enabled)
        .build()

private fun CommunicationGuidelinesDto.toProto(): CommunicationGuidelinesProto =
    CommunicationGuidelinesProto.newBuilder()
        .setEmailResponseLanguage(emailResponseLanguage ?: "")
        .setEmailSignature(emailSignature ?: "")
        .setCommentStyle(commentStyle ?: "")
        .setJiraCommentLanguage(jiraCommentLanguage ?: "")
        .setFormalityLevel(formalityLevel ?: "")
        .addAllCustomRules(customRules)
        .build()

private fun ApprovalGuidelinesDto.toProto(): ApprovalGuidelinesProto =
    ApprovalGuidelinesProto.newBuilder()
        .setAutoApproveCommit(autoApproveCommit.toProto())
        .setAutoApprovePush(autoApprovePush.toProto())
        .setAutoApproveEmail(autoApproveEmail.toProto())
        .setAutoApprovePrComment(autoApprovePrComment.toProto())
        .setAutoApproveChatReply(autoApproveChatReply.toProto())
        .setAutoApproveCodingDispatch(autoApproveCodingDispatch.toProto())
        .build()

private fun ApprovalRule.toProto(): ApprovalRuleProto {
    val builder = ApprovalRuleProto.newBuilder()
        .setEnabled(enabled)
        .setWhenRiskLevelBelow(whenRiskLevelBelow ?: "")
    whenConfidenceAbove?.let { builder.whenConfidenceAbove = it }
    return builder.build()
}

private fun GeneralGuidelinesDto.toProto(): GeneralGuidelinesProto =
    GeneralGuidelinesProto.newBuilder()
        .addAllCustomRules(customRules)
        .setNotes(notes ?: "")
        .build()

// ── Proto → DTO (for GuidelinesUpdateRequest input on Set) ─────────────

private fun com.jervis.contracts.server.GuidelinesUpdateRequest.toDto(): GuidelinesUpdateRequest {
    val scope = try {
        DtoGuidelinesScope.valueOf(scope.ifBlank { "GLOBAL" })
    } catch (_: Exception) {
        DtoGuidelinesScope.GLOBAL
    }
    return GuidelinesUpdateRequest(
        scope = scope,
        clientId = clientId.takeIf { it.isNotBlank() },
        projectId = projectId.takeIf { it.isNotBlank() },
        coding = if (hasCoding()) coding.toDto() else null,
        git = if (hasGit()) git.toDto() else null,
        review = if (hasReview()) review.toDto() else null,
        communication = if (hasCommunication()) communication.toDto() else null,
        approval = if (hasApproval()) approval.toDto() else null,
        general = if (hasGeneral()) general.toDto() else null,
    )
}

private fun CodingGuidelinesProto.toDto(): CodingGuidelinesDto =
    CodingGuidelinesDto(
        forbiddenPatterns = forbiddenPatternsList.map { it.toDto() },
        requiredPatterns = requiredPatternsList.map { it.toDto() },
        maxFileLines = if (hasMaxFileLines()) maxFileLines else null,
        maxFunctionLines = if (hasMaxFunctionLines()) maxFunctionLines else null,
        namingConventions = namingConventionsMap.toMap(),
        languageSpecific = languageSpecificMap.mapValues { it.value.toDto() },
        principles = principlesList.toList(),
    )

private fun PatternRuleProto.toDto(): PatternRule =
    PatternRule(
        pattern = pattern,
        description = description,
        severity = try { PatternSeverity.valueOf(severity) } catch (_: Exception) { PatternSeverity.WARNING },
        fileGlob = fileGlob.ifBlank { null },
    )

private fun LanguageRulesProto.toDto(): LanguageRulesDto =
    LanguageRulesDto(
        namingConvention = namingConvention.ifBlank { null },
        forbiddenImports = forbiddenImportsList.toList(),
        requiredImports = requiredImportsList.toList(),
        maxFileLines = if (hasMaxFileLines()) maxFileLines else null,
    )

private fun GitGuidelinesProto.toDto(): GitGuidelinesDto =
    GitGuidelinesDto(
        commitMessageTemplate = commitMessageTemplate.ifBlank { null },
        commitMessageValidators = commitMessageValidatorsList.toList(),
        branchNameTemplate = branchNameTemplate.ifBlank { null },
        requireJiraReference = requireJiraReference,
        squashOnMerge = squashOnMerge,
        protectedBranches = protectedBranchesList.toList(),
    )

private fun ReviewGuidelinesProto.toDto(): ReviewGuidelinesDto =
    ReviewGuidelinesDto(
        mustHaveTests = mustHaveTests,
        mustPassLint = mustPassLint,
        maxChangedFiles = if (hasMaxChangedFiles()) maxChangedFiles else null,
        maxChangedLines = if (hasMaxChangedLines()) maxChangedLines else null,
        forbiddenFileChanges = forbiddenFileChangesList.toList(),
        focusAreas = focusAreasList.toList(),
        checklistItems = checklistItemsList.map { it.toDto() },
        languageReviewRules = languageReviewRulesMap.mapValues { it.value.rulesList.toList() },
    )

private fun ReviewChecklistItemProto.toDto(): ReviewChecklistItem =
    ReviewChecklistItem(
        id = id,
        label = label,
        severity = try { PatternSeverity.valueOf(severity) } catch (_: Exception) { PatternSeverity.WARNING },
        enabled = enabled,
    )

private fun CommunicationGuidelinesProto.toDto(): CommunicationGuidelinesDto =
    CommunicationGuidelinesDto(
        emailResponseLanguage = emailResponseLanguage.ifBlank { null },
        emailSignature = emailSignature.ifBlank { null },
        commentStyle = commentStyle.ifBlank { null },
        jiraCommentLanguage = jiraCommentLanguage.ifBlank { null },
        formalityLevel = formalityLevel.ifBlank { null },
        customRules = customRulesList.toList(),
    )

private fun ApprovalGuidelinesProto.toDto(): ApprovalGuidelinesDto =
    ApprovalGuidelinesDto(
        autoApproveCommit = autoApproveCommit.toDto(),
        autoApprovePush = autoApprovePush.toDto(),
        autoApproveEmail = autoApproveEmail.toDto(),
        autoApprovePrComment = autoApprovePrComment.toDto(),
        autoApproveChatReply = autoApproveChatReply.toDto(),
        autoApproveCodingDispatch = autoApproveCodingDispatch.toDto(),
    )

private fun ApprovalRuleProto.toDto(): ApprovalRule =
    ApprovalRule(
        enabled = enabled,
        whenRiskLevelBelow = whenRiskLevelBelow.ifBlank { null },
        whenConfidenceAbove = if (hasWhenConfidenceAbove()) whenConfidenceAbove else null,
    )

private fun GeneralGuidelinesProto.toDto(): GeneralGuidelinesDto =
    GeneralGuidelinesDto(
        customRules = customRulesList.toList(),
        notes = notes.ifBlank { null },
    )
