package com.jervis.service.git

import com.jervis.common.types.ClientId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.git.CommitterInfoDto
import com.jervis.dto.git.GitAnalysisResultDto
import com.jervis.repository.ClientRepository
import com.jervis.service.indexing.git.GitRepositoryService
import kotlinx.coroutines.flow.collect
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Service for analyzing git repositories to extract commit patterns and configuration.
 *
 * Analyzes:
 * - Top committers (from git shortlog)
 * - Commit message patterns
 * - GPG signing usage
 */
@Service
class GitAnalysisService(
    private val clientRepository: ClientRepository,
    private val projectRepository: com.jervis.repository.ProjectRepository,
    private val connectionService: com.jervis.service.connection.ConnectionService,
    private val gitRepositoryService: GitRepositoryService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Analyze all git repositories for a client.
     * Clones repositories if needed, then analyzes commit history.
     */
    suspend fun analyzeClientGitRepositories(clientId: ClientId): GitAnalysisResultDto {
        logger.info { "GIT_ANALYSIS_START: clientId=$clientId" }

        val client = clientRepository.getById(clientId)
            ?: throw IllegalArgumentException("Client not found: $clientId")

        // Get all projects for this client
        val projects = mutableListOf<com.jervis.entity.ProjectDocument>()
        projectRepository.findAll().collect { project ->
            if (project.clientId == clientId) {
                projects.add(project)
            }
        }

        if (projects.isEmpty()) {
            logger.warn { "GIT_ANALYSIS_NO_PROJECTS: clientId=$clientId" }
            return GitAnalysisResultDto(
                topCommitters = emptyList(),
                detectedPattern = null,
                usesGpgSigning = false,
                gpgKeyIds = emptyList(),
                sampleMessages = emptyList(),
            )
        }

        // Collect all git resources from all projects
        val allGitResources = mutableListOf<Pair<com.jervis.entity.ProjectDocument, com.jervis.entity.ProjectResource>>()
        projects.forEach { project ->
            project.resources
                .filter { it.capability == ConnectionCapability.REPOSITORY }
                .forEach { resource -> allGitResources.add(project to resource) }
        }

        if (allGitResources.isEmpty()) {
            logger.warn { "GIT_ANALYSIS_NO_REPOS: clientId=$clientId projects=${projects.size}" }
            return GitAnalysisResultDto(
                topCommitters = emptyList(),
                detectedPattern = null,
                usesGpgSigning = false,
                gpgKeyIds = emptyList(),
                sampleMessages = emptyList(),
            )
        }

        logger.info { "GIT_ANALYSIS_FOUND_REPOS: clientId=$clientId count=${allGitResources.size}" }

        // Analyze each repository and merge results
        val allCommitters = mutableMapOf<String, CommitterInfoDto>() // key = "name <email>"
        val detectedPatterns = mutableListOf<String>()
        val allGpgKeys = mutableSetOf<String>()
        var gpgSignedRepos = 0
        val allSamples = mutableListOf<String>()

        for (projectResource in allGitResources) {
            val project = projectResource.first
            val resource = projectResource.second

            try {
                // Get repository path (must be already cloned for agent workspace)
                val repoPath = gitRepositoryService.getAgentRepoDir(project, resource)
                if (!repoPath.resolve(".git").toFile().exists()) {
                    logger.info { "GIT_ANALYSIS_SKIP_NOT_CLONED: project=${project.name} resource=${resource.resourceIdentifier}" }
                    continue
                }

                logger.info { "GIT_ANALYSIS_ANALYZING: project=${project.name} resource=${resource.resourceIdentifier} path=$repoPath" }

                val result = analyzeRepository(repoPath)

                // Merge committers
                result.topCommitters.forEach { committer ->
                    val key = "${committer.name} <${committer.email}>"
                    val existing = allCommitters[key]
                    if (existing != null) {
                        allCommitters[key] = existing.copy(commitCount = existing.commitCount + committer.commitCount)
                    } else {
                        allCommitters[key] = committer
                    }
                }

                // Collect patterns
                result.detectedPattern?.let { detectedPatterns.add(it) }

                // Merge GPG info
                if (result.usesGpgSigning) {
                    gpgSignedRepos++
                    allGpgKeys.addAll(result.gpgKeyIds)
                }

                // Collect samples
                allSamples.addAll(result.sampleMessages.take(3))

            } catch (e: Exception) {
                logger.error(e) { "GIT_ANALYSIS_ERROR: project=${project.name} resource=${resource.resourceIdentifier}" }
            }
        }

        // Sort committers by commit count and take top 10
        val topCommitters = allCommitters.values
            .sortedByDescending { it.commitCount }
            .take(10)

        // Pick most common pattern
        val mostCommonPattern = detectedPatterns
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        logger.info { "GIT_ANALYSIS_COMPLETE: clientId=$clientId topCommitters=${topCommitters.size} pattern=$mostCommonPattern gpgRepos=$gpgSignedRepos/${allGitResources.size}" }

        return GitAnalysisResultDto(
            topCommitters = topCommitters,
            detectedPattern = mostCommonPattern,
            usesGpgSigning = gpgSignedRepos > (allGitResources.size / 2),
            gpgKeyIds = allGpgKeys.toList(),
            sampleMessages = allSamples.take(10),
        )
    }

    /**
     * Analyze a single git repository at the given path.
     */
    private suspend fun analyzeRepository(repoPath: Path): GitAnalysisResultDto {
        logger.info { "Analyzing git repository: $repoPath" }

        val topCommitters = extractTopCommitters(repoPath)
        val pattern = detectCommitMessagePattern(repoPath)
        val (usesGpg, gpgKeys) = detectGpgSigning(repoPath)
        val samples = getSampleCommitMessages(repoPath)

        return GitAnalysisResultDto(
            topCommitters = topCommitters,
            detectedPattern = pattern,
            usesGpgSigning = usesGpg,
            gpgKeyIds = gpgKeys,
            sampleMessages = samples,
        )
    }

    /**
     * Extract top 10 committers from git shortlog.
     */
    private fun extractTopCommitters(repoPath: Path): List<CommitterInfoDto> {
        val result = gitRepositoryService.executeGitCommand(
            listOf("git", "shortlog", "-sn", "--all", "--no-merges"),
            workingDir = repoPath,
        )

        if (!result.success) {
            logger.warn { "Failed to extract committers: ${result.output}" }
            return emptyList()
        }

        // Parse: "   123  John Doe"
        return result.output.lines()
            .filter { it.isNotBlank() }
            .take(10)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    val count = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val name = parts[1]
                    // Get email from git log
                    val emailResult = gitRepositoryService.executeGitCommand(
                        listOf("git", "log", "--author=$name", "-1", "--pretty=format:%ae"),
                        workingDir = repoPath,
                    )
                    val email = if (emailResult.success) emailResult.output.trim() else ""
                    CommitterInfoDto(name = name, email = email, commitCount = count)
                } else {
                    null
                }
            }
    }

    /**
     * Detect common commit message pattern from recent commits.
     * Returns pattern with placeholders or null if no clear pattern detected.
     */
    private fun detectCommitMessagePattern(repoPath: Path): String? {
        val result = gitRepositoryService.executeGitCommand(
            listOf("git", "log", "--all", "--no-merges", "--pretty=format:%s", "-50"),
            workingDir = repoPath,
        )

        if (!result.success || result.output.isBlank()) {
            return null
        }

        val messages = result.output.lines().filter { it.isNotBlank() }

        // Try to detect common patterns:
        // 1. [PREFIX] message
        // 2. PREFIX: message
        // 3. PREFIX-123: message
        // 4. Just message

        val bracketPattern = Regex("^\\[([^]]+)]\\s+(.+)")
        val colonPattern = Regex("^([^:]+):\\s+(.+)")
        val ticketPattern = Regex("^([A-Z]+-\\d+):\\s+(.+)")

        var bracketCount = 0
        var colonCount = 0
        var ticketCount = 0

        messages.forEach { msg ->
            when {
                bracketPattern.matches(msg) -> bracketCount++
                ticketPattern.matches(msg) -> ticketCount++
                colonPattern.matches(msg) -> colonCount++
            }
        }

        val threshold = messages.size / 2

        return when {
            bracketCount > threshold -> "[\$project] \$message"
            ticketCount > threshold -> "\$task_number: \$message"
            colonCount > threshold -> "\$project: \$message"
            else -> null // No clear pattern
        }
    }

    /**
     * Detect if repository uses GPG signing.
     */
    private fun detectGpgSigning(repoPath: Path): Pair<Boolean, List<String>> {
        val result = gitRepositoryService.executeGitCommand(
            listOf("git", "log", "--show-signature", "--pretty=format:%G?,%GK", "-20"),
            workingDir = repoPath,
        )

        if (!result.success) {
            return false to emptyList()
        }

        val lines = result.output.lines().filter { it.isNotBlank() }
        val gpgKeyIds = mutableSetOf<String>()
        var signedCount = 0

        lines.forEach { line ->
            val parts = line.split(",")
            if (parts.size == 2) {
                val signature = parts[0] // G = good, B = bad, U = untrusted, N = no signature
                val keyId = parts[1]
                if (signature in setOf("G", "B", "U") && keyId.isNotBlank()) {
                    signedCount++
                    gpgKeyIds.add(keyId)
                }
            }
        }

        val usesGpg = signedCount > (lines.size / 2)
        return usesGpg to gpgKeyIds.toList()
    }

    /**
     * Get sample commit messages for user review.
     */
    private fun getSampleCommitMessages(repoPath: Path): List<String> {
        val result = gitRepositoryService.executeGitCommand(
            listOf("git", "log", "--all", "--no-merges", "--pretty=format:%s", "-10"),
            workingDir = repoPath,
        )

        return if (result.success) {
            result.output.lines().filter { it.isNotBlank() }.take(10)
        } else {
            emptyList()
        }
    }
}
