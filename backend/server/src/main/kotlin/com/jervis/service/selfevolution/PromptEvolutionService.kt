package com.jervis.service.selfevolution

import com.jervis.dto.selfevolution.PromptSectionType
import com.jervis.entity.LearnedBehaviorDocument
import com.jervis.entity.PromptSectionDocument
import com.jervis.entity.PromptVersionHistoryDocument
import com.jervis.entity.UserCorrectionDocument
import com.jervis.repository.LearnedBehaviorRepository
import com.jervis.repository.PromptSectionRepository
import com.jervis.repository.PromptVersionHistoryRepository
import com.jervis.repository.UserCorrectionRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * EPIC 13: System Prompt Self-Evolution Service.
 *
 * S1: MongoDB storage for dynamic prompt sections.
 * S2: Behavior learning (propose/approve rules from denial analysis).
 * S3: User correction persistence.
 * S4: Version history with rollback support.
 */
@Service
class PromptEvolutionService(
    private val sectionRepo: PromptSectionRepository,
    private val behaviorRepo: LearnedBehaviorRepository,
    private val correctionRepo: UserCorrectionRepository,
    private val historyRepo: PromptVersionHistoryRepository,
) {
    private val logger = KotlinLogging.logger {}

    // --- S1: Prompt Section CRUD ---

    /**
     * Assemble the full dynamic prompt for a client.
     * Returns base + learned_behaviors + user_corrections concatenated.
     */
    suspend fun assemblePrompt(clientId: String): String {
        val sections = sectionRepo.findByClientId(clientId).toList()

        val base = sections.find { it.type == PromptSectionType.BASE }?.content ?: ""
        val learned = sections.find { it.type == PromptSectionType.LEARNED_BEHAVIORS }?.content ?: ""
        val corrections = sections.find { it.type == PromptSectionType.USER_CORRECTIONS }?.content ?: ""

        // Also include approved individual behaviors and corrections
        val approvedBehaviors = behaviorRepo
            .findByClientIdAndApprovedByUser(clientId, true)
            .toList()
        val userCorrections = correctionRepo
            .findByClientId(clientId)
            .toList()

        return buildString {
            if (base.isNotBlank()) appendLine(base)
            if (learned.isNotBlank()) {
                appendLine("\n## Learned Behaviors")
                appendLine(learned)
            }
            if (approvedBehaviors.isNotEmpty()) {
                for (b in approvedBehaviors) {
                    appendLine("- ${b.rule}")
                }
            }
            if (corrections.isNotBlank()) {
                appendLine("\n## User Corrections")
                appendLine(corrections)
            }
            if (userCorrections.isNotEmpty()) {
                for (c in userCorrections) {
                    appendLine("- ${c.instruction}")
                }
            }
        }.trim()
    }

    /**
     * Update a prompt section, incrementing version and recording history.
     */
    suspend fun updateSection(
        clientId: String,
        type: PromptSectionType,
        content: String,
        reason: String? = null,
    ): PromptSectionDocument {
        val existing = sectionRepo.findByClientIdAndType(clientId, type)

        val doc = if (existing != null) {
            // Record history
            historyRepo.save(
                PromptVersionHistoryDocument(
                    clientId = clientId,
                    sectionType = type,
                    version = existing.version,
                    changeType = "UPDATE",
                    changeSummary = reason ?: "Section updated",
                    previousContent = existing.content,
                    newContent = content,
                ),
            )
            existing.content = content
            existing.version += 1
            existing.reason = reason
            existing.updatedAt = Instant.now()
            existing
        } else {
            // Create new
            PromptSectionDocument(
                clientId = clientId,
                type = type,
                content = content,
                reason = reason,
            )
        }

        val saved = sectionRepo.save(doc)
        logger.info { "Prompt section updated: client=$clientId, type=$type, v=${saved.version}" }
        return saved
    }

    // --- S2: Behavior Learning ---

    /**
     * Propose a learned behavior from denial analysis.
     * The behavior remains unapproved until the user confirms.
     */
    suspend fun proposeBehavior(
        clientId: String,
        rule: String,
        reason: String,
        learnedFrom: String,
    ): LearnedBehaviorDocument {
        val doc = LearnedBehaviorDocument(
            clientId = clientId,
            rule = rule,
            reason = reason,
            learnedFrom = learnedFrom,
        )
        val saved = behaviorRepo.save(doc)
        logger.info { "Behavior proposed: client=$clientId, rule='${rule.take(60)}...'" }
        return saved
    }

    /**
     * Approve a proposed behavior.
     */
    suspend fun approveBehavior(behaviorId: String): LearnedBehaviorDocument? {
        val doc = behaviorRepo.findById(org.bson.types.ObjectId(behaviorId)) ?: return null
        doc.approvedByUser = true
        return behaviorRepo.save(doc)
    }

    /**
     * Get pending (unapproved) behaviors for a client.
     */
    suspend fun getPendingBehaviors(clientId: String): List<LearnedBehaviorDocument> =
        behaviorRepo.findByClientIdAndApprovedByUser(clientId, false).toList()

    // --- S3: User Corrections ---

    /**
     * Store a user correction.
     */
    suspend fun addCorrection(
        clientId: String,
        instruction: String,
        context: String? = null,
        projectId: String? = null,
    ): UserCorrectionDocument {
        val doc = UserCorrectionDocument(
            clientId = clientId,
            instruction = instruction,
            context = context,
            projectId = projectId,
        )
        val saved = correctionRepo.save(doc)
        logger.info { "User correction added: client=$clientId, instruction='${instruction.take(60)}'" }
        return saved
    }

    /**
     * Get all corrections for a client.
     */
    suspend fun getCorrections(clientId: String): List<UserCorrectionDocument> =
        correctionRepo.findByClientId(clientId).toList()

    // --- S4: Version History ---

    /**
     * Get version history for a section.
     */
    suspend fun getVersionHistory(
        clientId: String,
        sectionType: PromptSectionType,
    ): List<PromptVersionHistoryDocument> =
        historyRepo.findByClientIdAndSectionTypeOrderByVersionDesc(clientId, sectionType).toList()

    /**
     * Rollback a section to a specific version.
     */
    suspend fun rollbackSection(
        clientId: String,
        sectionType: PromptSectionType,
        targetVersion: Int,
    ): PromptSectionDocument? {
        val history = getVersionHistory(clientId, sectionType)
        val target = history.find { it.version == targetVersion } ?: return null

        return updateSection(
            clientId = clientId,
            type = sectionType,
            content = target.previousContent ?: target.newContent,
            reason = "Rollback to version $targetVersion",
        )
    }
}
