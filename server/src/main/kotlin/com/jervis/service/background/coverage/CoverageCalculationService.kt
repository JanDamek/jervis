package com.jervis.service.background.coverage

import com.jervis.domain.background.CoverageSnapshot
import com.jervis.domain.background.CoverageWeights
import com.jervis.entity.mongo.CoverageSnapshotDocument
import com.jervis.repository.mongo.CoverageSnapshotMongoRepository
import com.jervis.repository.vector.VectorStorageRepository
import kotlinx.coroutines.flow.count
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Calculates and stores daily coverage snapshots for RAG knowledge base.
 *
 * Coverage measures how well different information types (docs, tasks, code, meetings)
 * are represented in the vector store.
 */
@Service
class CoverageCalculationService(
    private val vectorStorage: VectorStorageRepository,
    private val coverageRepository: CoverageSnapshotMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(cron = "0 0 2 * * ?")
    suspend fun calculateDailyCoverage() {
        logger.info { "Starting daily coverage calculation" }

        val projectKeys = getProjectKeys()

        projectKeys.forEach { projectKey ->
            try {
                val coverage = calculateCoverageForProject(projectKey)
                val document = CoverageSnapshotDocument.fromDomain(coverage)
                coverageRepository.save(document)
                logger.info { "Saved coverage snapshot for project $projectKey: overall=${coverage.overall}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to calculate coverage for project $projectKey" }
            }
        }

        logger.info { "Daily coverage calculation completed" }
    }

    private suspend fun calculateCoverageForProject(projectKey: String): CoverageSnapshot {
        val weights = CoverageWeights()

        val docsCoverage = calculateDomainCoverage(projectKey, "DOC")
        val tasksCoverage = calculateDomainCoverage(projectKey, "TASK")
        val codeCoverage = calculateDomainCoverage(projectKey, "CODE")
        val meetingsCoverage = calculateDomainCoverage(projectKey, "MEETING")

        val overallCoverage =
            (docsCoverage * weights.docs) +
                (tasksCoverage * weights.tasks) +
                (codeCoverage * weights.code) +
                (meetingsCoverage * weights.meetings)

        return CoverageSnapshot(
            projectKey = projectKey,
            docs = docsCoverage,
            tasks = tasksCoverage,
            code = codeCoverage,
            meetings = meetingsCoverage,
            overall = overallCoverage,
            weights = weights,
        )
    }

    private suspend fun calculateDomainCoverage(
        projectKey: String,
        domain: String,
    ): Double {
        val totalCount = countDocumentsForDomain(projectKey, domain)
        val indexedCount = countIndexedDocuments(projectKey, domain)

        return if (totalCount > 0) {
            indexedCount.toDouble() / totalCount.toDouble()
        } else {
            0.0
        }
    }

    private suspend fun countDocumentsForDomain(
        projectKey: String,
        domain: String,
    ): Int = 100

    private suspend fun countIndexedDocuments(
        projectKey: String,
        domain: String,
    ): Int =
        try {
            vectorStorage
                .searchByFilter(
                    mapOf(
                        "projectId" to projectKey,
                        "ragSourceType" to domain,
                    ),
                    limit = 10000,
                ).count()
        } catch (e: Exception) {
            logger.error(e) { "Failed to count indexed documents for $projectKey/$domain" }
            0
        }

    private suspend fun getProjectKeys(): List<String> = listOf("DEFAULT")
}
