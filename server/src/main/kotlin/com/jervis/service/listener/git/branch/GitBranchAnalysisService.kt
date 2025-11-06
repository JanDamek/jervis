package com.jervis.service.listener.git.branch

import com.jervis.domain.git.branch.BranchRef
import com.jervis.domain.git.branch.BranchStatusEnum
import com.jervis.domain.git.branch.BranchSummary
import com.jervis.domain.git.branch.ChangeTagEnum
import com.jervis.domain.git.branch.Dependencies
import com.jervis.domain.git.branch.DocumentationSummary
import com.jervis.domain.git.branch.OperationsImpact
import com.jervis.domain.git.branch.RepoId
import com.jervis.domain.git.branch.ScopeOfChange
import com.jervis.domain.git.branch.TestingSummary
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.git.GitRemoteClient
import com.jervis.service.git.collectWithLogging
import com.jervis.service.rag.VectorStoreIndexService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

/**
 * Branch-aware Git analysis and RAG embedding.
 *
 * Responsibilities:
 * - Discover remote branches and default branch
 * - Compute fork point against default branch
 * - Aggregate commit/file stats for forkPoint..HEAD per branch
 * - Build a deterministic narrative and embed into vector store
 */
@Service
class GitBranchAnalysisService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val vectorStoreIndexService: VectorStoreIndexService,
    private val gitRemoteClient: GitRemoteClient,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun indexAllBranches(
        project: ProjectDocument,
        projectPath: Path,
        maxBranches: Int = 50,
    ) {
        val discovery = listRemoteBranches(projectPath)
        val defaultBranch = discovery.defaultBranch
        val branches = discovery.branches.take(maxBranches)

        logger.info { "GIT_BRANCH: Discovered ${branches.size} branches (default=$defaultBranch) for project ${project.name}" }

        for (ref in branches) {
            runCatching {
                val summary = buildBranchSummary(projectPath, ref)
                indexBranchSummary(project, summary)
                logger.info { "GIT_BRANCH: Indexed summary for ${ref.name} head=${ref.headSha.take(8)}" }
            }.onFailure { e ->
                logger.error(e) { "GIT_BRANCH: Failed to index branch ${ref.name}" }
            }
        }
    }

    // ========== Discovery ==========

    data class DiscoveryResult(
        val defaultBranch: String,
        val branches: List<BranchRef>,
    )

    private suspend fun listRemoteBranches(projectPath: Path): DiscoveryResult =
        withContext(Dispatchers.IO) {
            val repoUrl = extractRepoUrlFromGitConfig(projectPath)

            if (repoUrl != null) {
                runCatching {
                    gitRemoteClient
                        .fetch(
                            repoUrl = repoUrl,
                            workingDir = projectPath,
                            branch = "HEAD",
                            envVars = emptyMap(),
                        ).collectWithLogging(logger, "branch-discovery")
                }.onFailure { e ->
                    logger.debug(e) { "GIT_BRANCH: fetch failed, continuing with existing refs" }
                }
            } else {
                logger.debug { "GIT_BRANCH: Cannot determine repo URL, skipping fetch" }
            }

            val default = resolveDefaultRemoteBranch(projectPath)
            val process =
                ProcessBuilder(
                    "git",
                    "for-each-ref",
                    "--format=%(refname:short) %(objectname)",
                    "refs/remotes/origin",
                ).directory(projectPath.toFile()).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val refs = mutableListOf<BranchRef>()
            reader.useLines { lines ->
                lines.forEach { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size == 2) {
                        val full = parts[0]
                        val sha = parts[1]
                        if (full == "origin/HEAD") return@forEach
                        val name = full.removePrefix("origin/")
                        refs.add(
                            BranchRef(
                                repoId = RepoId(projectPath.toString()),
                                name = name,
                                headSha = sha,
                                defaultBranch = default,
                            ),
                        )
                    }
                }
            }
            process.waitFor()
            DiscoveryResult(default, refs.sortedBy { it.name })
        }

    private fun resolveDefaultRemoteBranch(projectPath: Path): String =
        try {
            val p =
                ProcessBuilder("git", "symbolic-ref", "refs/remotes/origin/HEAD")
                    .directory(projectPath.toFile())
                    .start()
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            p.waitFor()
            val last = out.substringAfterLast('/')
            if (last.isBlank()) "main" else last
        } catch (e: Exception) {
            logger.debug(e) { "Could not resolve default remote branch, using 'main'" }
            "main"
        }

    // ========== Summary builder ==========

    private suspend fun buildBranchSummary(
        projectPath: Path,
        ref: BranchRef,
    ): BranchSummary =
        withContext(Dispatchers.IO) {
            val fork = computeForkPoint(projectPath, ref.defaultBranch, ref.name)
            val commitHashes = listCommitsBetween(projectPath, fork, ref.name)
            val commitSubjects = mutableListOf<String>()
            val files = mutableSetOf<String>()
            var addTotal = 0
            var delTotal = 0
            var testsTouched = false
            var docsTouched = false
            val docsFiles = mutableListOf<String>()
            var requiresMigration = false
            val migrationFiles = mutableListOf<String>()
            var configTouched = false

            commitHashes.forEach { sha ->
                val logLine = gitSingleLine(projectPath, listOf("git", "log", "--pretty=%s", "-n", "1", sha))
                if (logLine.isNotBlank()) commitSubjects.add(logLine)

                val show =
                    ProcessBuilder("git", "show", "--numstat", "--format=tformat:", sha)
                        .directory(projectPath.toFile())
                        .start()
                BufferedReader(InputStreamReader(show.inputStream)).useLines { lines ->
                    lines.forEach { l ->
                        val parts = l.split("\t")
                        if (parts.size == 3) {
                            val a = parts[0].toIntOrNull() ?: 0
                            val d = parts[1].toIntOrNull() ?: 0
                            val f = parts[2]
                            addTotal += a
                            delTotal += d
                            files.add(f)
                            val norm = f.lowercase()
                            if (norm.startsWith("src/test") || norm.contains("/test/")) testsTouched = true
                            if (norm.startsWith("docs/") || norm.contains("readme")) {
                                docsTouched = true
                                docsFiles.add(f)
                            }
                            if (norm.contains("db/migration") || norm.contains("migrations/")) {
                                requiresMigration = true
                                migrationFiles.add(f)
                            }
                            if (norm.contains("/helm/") || norm.contains("/k8s/") || norm.contains("src/main/resources")) {
                                configTouched = true
                            }
                        }
                    }
                }
                show.waitFor()
            }

            val modules = files.map { it.substringBefore('/').ifBlank { it } }.distinct().sorted()
            val tagsHistogram = detectChangeTags(commitSubjects, files.toList())
            val issueKeys = extractIssueKeys(ref.name, commitSubjects)

            val goal = if (issueKeys.size == 1) "Work on ${issueKeys.first()}" else "Changes on branch ${ref.name}"
            val risks = mutableListOf<String>()
            if (tagsHistogram[ChangeTagEnum.BREAKING] ?: 0 > 0) risks.add("Breaking changes present")
            if (requiresMigration) risks.add("Database migration required")

            val testingSummary =
                TestingSummary(
                    testsTouched,
                    addedTests = 0,
                    coverageHint = if (testsTouched) null else "Consider adding tests",
                )
            val docSummary = DocumentationSummary(docsTouched, docsFiles)
            val operations = OperationsImpact(requiresMigration, migrationFiles, configTouched)
            val scope = ScopeOfChange(modules, files.size, addTotal, delTotal, tagsHistogram)
            val deps = Dependencies(issueKeys = issueKeys, relatedBranches = emptySet(), relatedPrs = emptySet())
            val status = BranchStatusEnum.InProgress
            val acceptance = buildAcceptanceChecklist(testingSummary, docSummary, operations)

            BranchSummary(
                repoId = ref.repoId,
                branch = ref.name,
                base = ref.defaultBranch,
                forkPointSha = fork,
                headSha = ref.headSha,
                goal = goal,
                scopeOfChange = scope,
                dependencies = deps,
                risks = risks,
                operations = operations,
                testing = testingSummary,
                documentation = docSummary,
                status = status,
                acceptance = acceptance,
            )
        }

    private fun detectChangeTags(
        subjects: List<String>,
        files: List<String>,
    ): Map<ChangeTagEnum, Int> {
        val counters = mutableMapOf<ChangeTagEnum, Int>().withDefault { 0 }
        subjects.forEach { s ->
            val lower = s.lowercase()

            fun inc(tag: ChangeTagEnum) {
                counters[tag] = counters.getValue(tag) + 1
            }
            when {
                lower.startsWith("feat") -> inc(ChangeTagEnum.FEAT)
                lower.startsWith("fix") -> inc(ChangeTagEnum.FIX)
                lower.startsWith("docs") -> inc(ChangeTagEnum.DOCS)
                lower.startsWith("test") -> inc(ChangeTagEnum.TEST)
                lower.startsWith("chore") -> inc(ChangeTagEnum.CHORE)
                lower.startsWith("refactor") -> { // no-op
                }
            }
            if (lower.contains("breaking") || lower.contains("!")) inc(ChangeTagEnum.BREAKING)
        }
        files.forEach { f ->
            val n = f.lowercase()
            when {
                n.endsWith(".md") || n.startsWith("docs/") ->
                    counters[ChangeTagEnum.DOCS] =
                        counters.getValue(ChangeTagEnum.DOCS) + 1

                n.contains("/test/") || n.startsWith("src/test") ->
                    counters[ChangeTagEnum.TEST] =
                        counters.getValue(ChangeTagEnum.TEST) + 1

                n.contains("/helm/") || n.contains("/k8s/") || n.contains("src/main/resources") ->
                    counters[ChangeTagEnum.CONFIG] =
                        counters.getValue(ChangeTagEnum.CONFIG) + 1
            }
        }
        return counters
    }

    private fun extractIssueKeys(
        branchName: String,
        subjects: List<String>,
    ): Set<String> {
        val regex = Regex("[A-Z][A-Z0-9]+-\\d+")
        val found = mutableSetOf<String>()
        regex.findAll(branchName).forEach { found.add(it.value) }
        subjects.forEach { s -> regex.findAll(s).forEach { found.add(it.value) } }
        return found
    }

    private fun buildAcceptanceChecklist(
        testing: TestingSummary,
        docs: DocumentationSummary,
        ops: OperationsImpact,
    ): List<String> {
        val items = mutableListOf<String>()
        if (!testing.testsTouched) items.add("Add or update tests for changed code")
        if (!docs.docsChanged) items.add("Update documentation if public API changed")
        if (ops.requiresMigration && ops.migrationFiles.isEmpty()) items.add("Provide DB migration scripts")
        return items
    }

    private fun narrative(summary: BranchSummary): String =
        buildString {
            appendLine("Branch: ${summary.branch} (base: ${summary.base})")
            appendLine("Fork point: ${summary.forkPointSha.take(8)}  Head: ${summary.headSha.take(8)}")
            appendLine("Goal: ${summary.goal}")
            appendLine(
                "Scope: files=${summary.scopeOfChange.fileCount}, +${summary.scopeOfChange.linesAdded}/-${summary.scopeOfChange.linesDeleted}; tags=" +
                    summary.scopeOfChange.tagsHistogram.entries.joinToString(
                        prefix = "{",
                        postfix = "}",
                    ) { it.key.name + ":" + it.value },
            )
            if (summary.scopeOfChange.modules.isNotEmpty()) {
                appendLine("Modules: ${summary.scopeOfChange.modules.joinToString(", ")}")
            }
            appendLine(
                "Dependencies: issues=${summary.dependencies.issueKeys.toList().sorted()} " +
                    "branches=${
                        summary.dependencies.relatedBranches.toList().sorted()
                    } PRs=${summary.dependencies.relatedPrs.toList().sorted()}",
            )
            if (summary.risks.isNotEmpty()) {
                appendLine("Risks:")
                summary.risks.forEach { appendLine("- $it") }
            }
            appendLine(
                "Operations: requiresMigration=${summary.operations.requiresMigration}, files=${
                    summary.operations.migrationFiles.take(
                        5,
                    )
                }, configTouched=${summary.operations.configTouched}",
            )
            appendLine(
                "Testing: changed=${summary.testing.testsTouched}, addedTests=${summary.testing.addedTests}, coverageHint=${summary.testing.coverageHint ?: ""}",
            )
            appendLine(
                "Docs: changed=${summary.documentation.docsChanged}, files=${
                    summary.documentation.docsFiles.take(
                        5,
                    )
                }",
            )
            if (summary.acceptance.isNotEmpty()) {
                appendLine("Acceptance before merge:")
                summary.acceptance.forEach { appendLine("- $it") }
            }
        }.trim()

    private suspend fun extractRepoUrlFromGitConfig(gitDir: Path): String? =
        withContext(Dispatchers.IO) {
            try {
                val processBuilder =
                    ProcessBuilder("git", "config", "--get", "remote.origin.url")
                        .directory(gitDir.toFile())
                        .redirectErrorStream(true)

                val process = processBuilder.start()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    process.inputStream.bufferedReader().use { it.readText().trim() }
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.debug(e) { "Could not extract repo URL from git config at $gitDir" }
                null
            }
        }

    // ========== Indexing ==========

    private suspend fun indexBranchSummary(
        project: ProjectDocument,
        summary: BranchSummary,
    ) {
        val sourceId = "branch-summary:${summary.branch}@${summary.headSha}"
        val text = narrative(summary)
        val hasChanged =
            vectorStoreIndexService.hasContentChanged(RagSourceType.GIT_HISTORY, sourceId, project.id, text)
        if (!hasChanged) {
            logger.debug { "GIT_BRANCH: Skipping ${summary.branch} - summary unchanged" }
            return
        }

        val embedding = embeddingGateway.callEmbedding(ModelTypeEnum.EMBEDDING_TEXT, text)
        val rag =
            RagDocument(
                projectId = project.id,
                clientId = project.clientId,
                text = text,
                ragSourceType = RagSourceType.GIT_HISTORY,
                subject = "Branch summary ${summary.branch}",
                timestamp = summary.generatedAt.toString(),
                parentRef = summary.headSha,
                branch = summary.branch,
            )
        val vectorId = vectorStorage.store(ModelTypeEnum.EMBEDDING_TEXT, rag, embedding)
        vectorStoreIndexService.trackIndexed(
            projectId = project.id,
            clientId = project.clientId,
            branch = summary.branch,
            sourceType = RagSourceType.GIT_HISTORY,
            sourceId = sourceId,
            vectorStoreId = vectorId,
            vectorStoreName = "git-branch-summary",
            content = text,
            commitHash = summary.headSha,
        )
    }

    // ========== Git helpers ==========

    private fun computeForkPoint(
        projectPath: Path,
        base: String,
        branch: String,
    ): String {
        val p =
            ProcessBuilder("git", "merge-base", "origin/$base", "origin/$branch")
                .directory(projectPath.toFile())
                .start()
        val out = p.inputStream.bufferedReader().use { it.readText().trim() }
        p.waitFor()
        return out.ifBlank { gitSingleLine(projectPath, listOf("git", "merge-base", base, branch)) }
    }

    private fun listCommitsBetween(
        projectPath: Path,
        forkPoint: String,
        branch: String,
    ): List<String> {
        val p =
            ProcessBuilder("git", "log", "--pretty=%H", "$forkPoint..origin/$branch")
                .directory(projectPath.toFile())
                .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out.lines().filter { it.isNotBlank() }
    }

    private fun gitSingleLine(
        projectPath: Path,
        cmd: List<String>,
    ): String {
        val p = ProcessBuilder(cmd).directory(projectPath.toFile()).start()
        val out = p.inputStream.bufferedReader().use { it.readText().trim() }
        p.waitFor()
        return out
    }
}
