package com.jervis.service.indexing.git

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ProjectDocument
import com.jervis.entity.ProjectResource
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.knowledgebase.model.CpgIngestRequest
import com.jervis.knowledgebase.model.GitStructureIngestRequest
import com.jervis.knowledgebase.model.GitBranchInfo
import com.jervis.knowledgebase.model.GitFileContent
import com.jervis.knowledgebase.model.GitFileInfo
import com.jervis.repository.ProjectRepository
import com.jervis.service.background.TaskService
import com.jervis.service.indexing.git.state.GitCommitDocument
import com.jervis.service.indexing.git.state.GitCommitRepository
import com.jervis.service.indexing.git.state.GitCommitState
import com.jervis.service.indexing.git.state.GitCommitStateManager
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for Git repositories.
 *
 * Picks up NEW GitCommitDocuments from MongoDB and creates GIT_PROCESSING tasks
 * with rich, structured content for the KB:
 *
 * For FIRST commit on a branch (initial index):
 *   - Repository overview: tech stack, file tree, README
 *   - Recent commit history summary
 *   - Documentation files
 *
 * For subsequent commits (incremental):
 *   - Commit message and metadata
 *   - Diff (patch) of changes
 *   - Files changed summary
 *
 * Branch isolation: content is always scoped to the specific branch.
 * KB stores this per (projectId + branch) so branches never mix.
 */
@Service
@Order(11)
class GitContinuousIndexer(
    private val stateManager: GitCommitStateManager,
    private val gitCommitRepository: GitCommitRepository,
    private val taskService: TaskService,
    private val gitRepositoryService: GitRepositoryService,
    private val projectRepository: ProjectRepository,
    private val knowledgeService: KnowledgeService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting GitContinuousIndexer (MongoDB -> Knowledgebase)..." }
        scope.launch {
            delay(20_000) // Let repos clone first
            while (isActive) {
                try {
                    processNewCommits()
                } catch (e: Exception) {
                    logger.error(e) { "GitContinuousIndexer cycle error" }
                }
                delay(15_000) // Check every 15 seconds
            }
        }
    }

    private suspend fun processNewCommits() {
        val newCommits = gitCommitRepository.findByStateOrderByCommitDateDesc(GitCommitState.NEW).toList()
        if (newCommits.isEmpty()) return

        logger.info { "Processing ${newCommits.size} new git commits" }

        // Group by project+branch for efficient processing
        val grouped = newCommits.groupBy { Pair(it.projectId, it.branch) }

        for ((key, commits) in grouped) {
            val (projectId, branch) = key
            if (projectId == null) continue

            try {
                processCommitGroup(projectId, branch, commits)
            } catch (e: Exception) {
                logger.error(e) { "Failed to process commits for project $projectId branch $branch" }
                commits.forEach { stateManager.markAsFailed(it, e.message ?: "Unknown error") }
            }
        }
    }

    private suspend fun processCommitGroup(
        projectId: org.bson.types.ObjectId,
        branch: String,
        commits: List<GitCommitDocument>,
    ) {
        val project = projectRepository.getById(com.jervis.common.types.ProjectId(projectId))
        if (project == null) {
            logger.warn { "Project $projectId not found, skipping commits" }
            commits.forEach { stateManager.markAsFailed(it, "Project not found") }
            return
        }

        // Find the repo resource and directory
        val repoResource = project.resources.firstOrNull { it.capability == ConnectionCapability.REPOSITORY }
        if (repoResource == null) {
            logger.warn { "No REPOSITORY resource for project ${project.name}" }
            commits.forEach { stateManager.markAsFailed(it, "No repository resource") }
            return
        }

        val repoDir = gitRepositoryService.getRepoDir(project, repoResource)

        // Check if this is the first indexing for this branch (initial index)
        val existingIndexed = gitCommitRepository.findByProjectIdAndStateOrderByCommitDateAsc(
            projectId, GitCommitState.INDEXED,
        ).toList()
        val isInitialIndex = existingIndexed.none { it.branch == branch }

        if (isInitialIndex) {
            // Create a rich overview document for the branch
            createBranchOverviewTask(project, repoResource, repoDir, branch, commits.first())
            // Create structural graph nodes (repository, branches, files) — no LLM
            createStructuralIndexTask(project, repoResource, repoDir, branch, commits.first())
            // Dispatch Joern CPG deep analysis (async, after structural nodes exist)
            createCpgAnalysisTask(project, repoDir, branch, commits.first())
        }

        // Process individual commits (newest first, limit to avoid flooding)
        val toProcess = commits.take(20) // Process max 20 at a time
        for (commit in toProcess) {
            processCommit(project, repoDir, commit, branch)
        }

        // Mark remaining as indexed without detailed processing
        val remaining = commits.drop(20)
        for (commit in remaining) {
            stateManager.markAsIndexed(commit)
            logger.debug { "Bulk-marked commit ${commit.commitHash.take(8)} as indexed (overflow)" }
        }
    }

    /**
     * Create a rich overview document for initial branch indexing.
     * This gives agents a complete picture of the repository.
     */
    private suspend fun createBranchOverviewTask(
        project: ProjectDocument,
        resource: ProjectResource,
        repoDir: Path,
        branch: String,
        triggerCommit: GitCommitDocument,
    ) {
        val fileTree = gitRepositoryService.getFileTree(repoDir)
        val techStack = gitRepositoryService.detectProjectStack(repoDir)
        val recentChanges = gitRepositoryService.getRecentChangeSummary(repoDir, branch, 20)
        val docs = gitRepositoryService.readDocumentationFiles(repoDir)

        val content = buildString {
            appendLine("# Repository Overview: ${resource.resourceIdentifier}")
            appendLine("**Branch:** $branch")
            appendLine("**Project:** ${project.name}")
            appendLine()

            appendLine("## Tech Stack")
            appendLine(techStack)
            appendLine()

            appendLine("## File Structure")
            appendLine(fileTree)
            appendLine()

            appendLine("## Recent Changes ($branch)")
            appendLine(recentChanges)
            appendLine()

            // Include documentation files
            for ((filename, fileContent) in docs) {
                appendLine("## Documentation: $filename")
                appendLine(fileContent)
                appendLine()
            }
        }

        taskService.createTask(
            taskType = TaskTypeEnum.GIT_PROCESSING,
            content = content,
            projectId = ProjectId(project.id.value),
            clientId = ClientId(triggerCommit.clientId),
            correlationId = "git-overview:${resource.resourceIdentifier}:$branch",
            sourceUrn = SourceUrn.git(
                projectId = project.id,
                commitHash = "overview-$branch",
            ),
            taskName = "Repo overview: ${resource.resourceIdentifier}/$branch",
        )

        logger.info { "Created branch overview task for ${resource.resourceIdentifier}/$branch" }
    }

    /**
     * Create structural graph nodes for the repository directly via KB RPC.
     *
     * Bypasses LLM — sends structured data (files, branches) to KB service
     * which creates ArangoDB graph nodes for repository→branch→file hierarchy.
     * This enables the orchestrator to search for files/classes by branch.
     */
    private suspend fun createStructuralIndexTask(
        project: ProjectDocument,
        resource: ProjectResource,
        repoDir: Path,
        branch: String,
        triggerCommit: GitCommitDocument,
    ) {
        try {
            val defaultBranch = gitRepositoryService.detectDefaultBranch(repoDir)
            val files = gitRepositoryService.getFileListWithMetadata(repoDir)
            val branches = gitRepositoryService.getBranchMetadata(repoDir, defaultBranch)

            // Read source file contents for tree-sitter parsing in KB service
            val fileContents = gitRepositoryService.readSourceFileContents(files, repoDir)
            logger.debug { "Read ${fileContents.size} source file contents for tree-sitter (${fileContents.sumOf { it.second.length }} bytes)" }

            val request = GitStructureIngestRequest(
                clientId = triggerCommit.clientId.toHexString(),
                projectId = project.id.value.toHexString(),
                repositoryIdentifier = resource.resourceIdentifier,
                branch = branch,
                defaultBranch = defaultBranch,
                branches = branches.map { b ->
                    GitBranchInfo(
                        name = b.name,
                        isDefault = b.isDefault,
                        status = b.status,
                        lastCommitHash = b.lastCommitHash,
                    )
                },
                files = files.map { f ->
                    GitFileInfo(
                        path = f.path,
                        extension = f.extension,
                        language = f.language,
                        sizeBytes = f.sizeBytes,
                    )
                },
                fileContents = fileContents.map { (path, content) ->
                    GitFileContent(path = path, content = content)
                },
            )

            val result = knowledgeService.ingestGitStructure(request)
            logger.info {
                "Structural ingest for ${resource.resourceIdentifier}/$branch: " +
                    "nodes=${result.nodesCreated} edges=${result.edgesCreated} " +
                    "files=${result.filesIndexed} updated=${result.nodesUpdated}"
            }
        } catch (e: Exception) {
            logger.error(e) { "Structural ingest failed for ${resource.resourceIdentifier}/$branch" }
        }
    }

    /**
     * Dispatch Joern CPG deep analysis for a branch.
     *
     * Runs after structural ingest (tree-sitter) completes, so method/class
     * nodes already exist in ArangoDB. Joern adds semantic edges:
     * - calls (method → method call graph)
     * - extends (class → class inheritance)
     * - uses_type (class → class type references)
     *
     * This is async and may take 60-120s for medium repos.
     */
    private suspend fun createCpgAnalysisTask(
        project: ProjectDocument,
        repoDir: Path,
        branch: String,
        triggerCommit: GitCommitDocument,
    ) {
        try {
            val request = CpgIngestRequest(
                clientId = triggerCommit.clientId.toHexString(),
                projectId = project.id.value.toHexString(),
                branch = branch,
                workspacePath = repoDir.toAbsolutePath().toString(),
            )

            val result = knowledgeService.ingestCpg(request)
            logger.info {
                "CPG analysis for ${project.name}/$branch: " +
                    "methods_enriched=${result.methodsEnriched} " +
                    "calls=${result.callsEdges} extends=${result.extendsEdges} " +
                    "uses_type=${result.usesTypeEdges}"
            }
        } catch (e: Exception) {
            // CPG analysis failure is non-fatal — structural graph is still usable
            logger.error(e) { "CPG analysis failed for ${project.name}/$branch (non-fatal)" }
        }
    }

    /**
     * Process a single commit: create task with diff and metadata.
     */
    private suspend fun processCommit(
        project: ProjectDocument,
        repoDir: Path,
        doc: GitCommitDocument,
        branch: String,
    ) {
        val projectId = doc.projectId ?: return

        logger.debug { "Processing commit ${doc.commitHash.take(8)} on $branch" }

        val diff = gitRepositoryService.getCommitDiff(repoDir, doc.commitHash)

        val content = buildString {
            appendLine("# Git Commit: ${doc.commitHash.take(8)}")
            appendLine("**Branch:** $branch")
            appendLine("**Author:** ${doc.author}")
            appendLine("**Date:** ${doc.commitDate}")
            appendLine("**Message:** ${doc.message}")
            appendLine()

            if (diff.isNotBlank()) {
                appendLine("## Changes")
                appendLine("```diff")
                appendLine(diff)
                appendLine("```")
            }
        }

        taskService.createTask(
            taskType = TaskTypeEnum.GIT_PROCESSING,
            content = content,
            projectId = ProjectId(projectId),
            clientId = ClientId(doc.clientId),
            correlationId = "git:${doc.commitHash}",
            sourceUrn = SourceUrn.git(
                projectId = ProjectId(projectId),
                commitHash = doc.commitHash,
            ),
            taskName = "${doc.commitHash.take(8)}: ${(doc.message ?: "").take(100)}",
        )

        stateManager.markAsIndexed(doc)
        logger.info { "Created GIT_PROCESSING task for commit ${doc.commitHash.take(8)} on $branch" }
    }
}
