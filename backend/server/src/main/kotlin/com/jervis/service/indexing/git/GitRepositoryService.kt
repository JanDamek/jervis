package com.jervis.service.indexing.git

import com.jervis.common.types.ConnectionId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ProjectDocument
import com.jervis.entity.ProjectResource
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.ProjectRepository
import com.jervis.service.connection.ConnectionService
import com.jervis.service.indexing.git.state.GitCommitInfo
import com.jervis.service.indexing.git.state.GitCommitStateManager
import com.jervis.service.error.ErrorLogService
import com.jervis.service.oauth2.OAuth2Service
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.dto.connection.ConnectionStateEnum
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

/**
 * Thrown when git operations fail due to authentication/authorization errors.
 * Signals that the connection credentials are invalid and should be marked INVALID.
 */
class GitAuthenticationException(message: String) : RuntimeException(message)

/** Patterns in git error output that indicate credential/auth failures. */
private val AUTH_ERROR_PATTERNS = listOf(
    "HTTP Basic: Access denied",
    "Authentication failed",
    "returned error: 401",
    "returned error: 403",
    "could not read Username",
    "terminal prompts disabled",
    "Invalid username or password",
    "Permission denied",
    "not found",
    "returned error: 404",
)

/**
 * Manages git repository cloning, pulling, and commit discovery.
 *
 * Responsibilities:
 * 1. Clone repositories into project workspace directories on startup
 * 2. Pull latest changes during polling cycles
 * 3. Parse git log to discover new commits per branch
 * 4. Build structured content for KB indexation (file tree, README, diffs)
 *
 * Directory layout:
 *   {data}/clients/{clientId}/projects/{projectId}/git/{resourceId}/
 *     └── (cloned repo contents)
 *
 * Branch isolation: each branch is tracked separately in GitCommitDocument.
 * Content indexed per branch - branches never mix in KB.
 */
@Service
class GitRepositoryService(
    private val directoryStructureService: DirectoryStructureService,
    private val projectRepository: ProjectRepository,
    private val connectionService: ConnectionService,
    private val commitStateManager: GitCommitStateManager,
    private val oauth2Service: OAuth2Service,
    private val errorLogService: ErrorLogService,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @PostConstruct
    fun syncAllOnStartup() {
        // Mark all directories as safe (Docker runs as root, PVC may have different ownership)
        executeGitCommand(listOf("git", "config", "--global", "--add", "safe.directory", "*"), workingDir = null)

        scope.launch {
            delay(15_000) // Let Spring context fully start
            logger.info { "GitRepositoryService: syncing all project repositories on startup..." }
            try {
                syncAllProjects()
            } catch (e: Exception) {
                logger.error(e) { "GitRepositoryService: startup sync failed" }
            }
        }
    }

    /**
     * Sync all projects that have REPOSITORY resources.
     * Called on startup and can be triggered manually.
     */
    suspend fun syncAllProjects() {
        val projects = projectRepository.findAll().toList()
        for (project in projects) {
            val repoResources = project.resources.filter { it.capability == ConnectionCapability.REPOSITORY }
            for (resource in repoResources) {
                try {
                    syncRepository(project, resource)
                } catch (e: GitAuthenticationException) {
                    logger.error { "Auth failed for ${resource.resourceIdentifier}: ${e.message}" }
                    invalidateConnection(resource.connectionId)
                    errorLogService.recordError(e, project.clientId.value, project.id.value)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to sync repo ${resource.resourceIdentifier} for project ${project.name}" }
                }
            }
        }
    }

    /**
     * Sync a single project's repositories.
     * Called when project resources change.
     */
    suspend fun syncProjectRepositories(project: ProjectDocument) {
        val repoResources = project.resources.filter { it.capability == ConnectionCapability.REPOSITORY }
        for (resource in repoResources) {
            try {
                syncRepository(project, resource)
            } catch (e: GitAuthenticationException) {
                logger.error { "Auth failed for ${resource.resourceIdentifier}: ${e.message}" }
                invalidateConnection(resource.connectionId)
                errorLogService.recordError(e, project.clientId.value, project.id.value)
            } catch (e: Exception) {
                logger.error(e) { "Failed to sync repo ${resource.resourceIdentifier} for project ${project.name}" }
            }
        }
    }

    /**
     * Clone or pull a repository, then discover new commits on default branch.
     */
    suspend fun syncRepository(
        project: ProjectDocument,
        resource: ProjectResource,
    ) {
        val initialConnection = connectionService.findById(ConnectionId(resource.connectionId))
        if (initialConnection == null) {
            logger.warn { "Connection ${resource.connectionId} not found for resource ${resource.resourceIdentifier}" }
            return
        }

        // Skip INVALID connections
        if (initialConnection.state == ConnectionStateEnum.INVALID) {
            logger.info { "Skipping sync for INVALID connection ${initialConnection.id} (${initialConnection.name})" }
            return
        }

        // Refresh OAuth2 token if expired (before using bearerToken for git ops)
        val connection = if (oauth2Service.refreshAccessToken(initialConnection)) {
            connectionService.findById(ConnectionId(resource.connectionId))!!
        } else {
            initialConnection
        }

        val repoUrl = buildRepoUrl(connection, resource)
        if (repoUrl.isNullOrBlank()) {
            logger.warn { "Cannot determine repo URL for ${resource.resourceIdentifier}" }
            return
        }

        val repoDir = getRepoDir(project, resource)

        val isCloned = repoDir.resolve(".git").exists()

        if (!isCloned) {
            logger.info { "Cloning ${resource.resourceIdentifier} into $repoDir" }
            cloneRepository(repoUrl, repoDir, connection)
        } else {
            logger.info { "Pulling ${resource.resourceIdentifier} in $repoDir" }
            fetchAll(repoDir, connection)
        }

        // Discover commits on default branch
        val defaultBranch = detectDefaultBranch(repoDir)
        checkoutBranch(repoDir, defaultBranch)
        discoverNewCommits(project, resource, repoDir, defaultBranch)
    }

    /**
     * Mark a connection as INVALID if it isn't already.
     */
    private suspend fun invalidateConnection(connectionId: ObjectId) {
        val conn = connectionService.findById(ConnectionId(connectionId)) ?: return
        if (conn.state != ConnectionStateEnum.INVALID) {
            connectionService.save(conn.copy(state = ConnectionStateEnum.INVALID))
            logger.warn { "Connection ${conn.id} (${conn.name}) marked INVALID" }
        }
    }

    /**
     * Get the local directory for a repository resource.
     */
    fun getRepoDir(project: ProjectDocument, resource: ProjectResource): Path {
        val gitDir = directoryStructureService.projectGitDir(project.clientId, project.id)
        val safeId = resource.resourceIdentifier.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return gitDir.resolve(safeId).also {
            if (!it.exists()) Files.createDirectories(it)
        }
    }

    /**
     * Build full clone URL from connection + resource identifier.
     */
    private fun buildRepoUrl(connection: ConnectionDocument, resource: ProjectResource): String? {
        // If connection has explicit gitRemoteUrl, use it
        if (!connection.gitRemoteUrl.isNullOrBlank()) return connection.gitRemoteUrl

        val baseUrl = connection.baseUrl.trimEnd('/')
        val identifier = resource.resourceIdentifier

        // GitHub API URL (api.github.com) → clone URL (github.com)
        val cloneBase = when {
            baseUrl.contains("api.github.com") -> "https://github.com"
            else -> baseUrl
        }

        return when {
            cloneBase.endsWith(".git") -> cloneBase
            else -> "$cloneBase/$identifier.git"
        }
    }

    /**
     * Build environment variables for git auth.
     */
    private fun buildAuthEnv(connection: ConnectionDocument): Map<String, String> {
        val env = mutableMapOf<String, String>()

        // For HTTPS with token: use GIT_ASKPASS approach
        if (!connection.bearerToken.isNullOrBlank()) {
            // credential.helper approach doesn't need env vars,
            // we configure it in the clone command instead
        }

        return env
    }

    private suspend fun cloneRepository(
        repoUrl: String,
        targetDir: Path,
        connection: ConnectionDocument,
    ) = withContext(Dispatchers.IO) {
        val authUrl = injectCredentials(repoUrl, connection)

        val cmd = listOf(
            "git", "clone",
            "--depth", "50", // Shallow clone for speed, enough history for indexing
            authUrl,
            targetDir.toString(),
        )

        val result = executeGitCommand(cmd, workingDir = null)
        if (!result.success) {
            val output = result.output
            if (AUTH_ERROR_PATTERNS.any { output.contains(it, ignoreCase = true) }) {
                throw GitAuthenticationException("Git auth failed cloning $repoUrl: $output")
            }
            throw RuntimeException("Clone failed for $repoUrl: $output")
        }

        // Strip credentials from remote URL after clone
        val safeUrl = repoUrl.replace(Regex("://[^@]+@"), "://")
        executeGitCommand(
            listOf("git", "remote", "set-url", "origin", safeUrl),
            workingDir = targetDir,
        )

        // Store credentials in git credential helper
        configureCredentials(targetDir, connection)

        logger.info { "Cloned $repoUrl into $targetDir" }
    }

    private suspend fun fetchAll(
        repoDir: Path,
        connection: ConnectionDocument,
    ) = withContext(Dispatchers.IO) {
        configureCredentials(repoDir, connection)

        val result = executeGitCommand(
            listOf("git", "fetch", "--all", "--prune"),
            workingDir = repoDir,
        )
        if (!result.success) {
            val output = result.output
            if (AUTH_ERROR_PATTERNS.any { output.contains(it, ignoreCase = true) }) {
                throw GitAuthenticationException("Git auth failed in $repoDir: $output")
            }
            logger.warn { "Fetch failed in $repoDir (non-auth error, skipping): $output" }
        }
    }

    private fun configureCredentials(repoDir: Path, connection: ConnectionDocument) {
        if (!connection.bearerToken.isNullOrBlank()) {
            // Configure credential helper to inject token
            val helperScript = repoDir.resolve(".git").resolve("credential-helper.sh")
            val token = connection.bearerToken
            Files.writeString(
                helperScript,
                """#!/bin/sh
echo "username=oauth2"
echo "password=$token"
""",
            )
            try {
                val perms = setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                )
                Files.setPosixFilePermissions(helperScript, perms)
            } catch (_: Exception) {
                // Non-POSIX filesystem
            }
            executeGitCommand(
                listOf("git", "config", "credential.helper", helperScript.toString()),
                workingDir = repoDir,
            )
        } else if (!connection.username.isNullOrBlank() && !connection.password.isNullOrBlank()) {
            val helperScript = repoDir.resolve(".git").resolve("credential-helper.sh")
            Files.writeString(
                helperScript,
                """#!/bin/sh
echo "username=${connection.username}"
echo "password=${connection.password}"
""",
            )
            try {
                val perms = setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                )
                Files.setPosixFilePermissions(helperScript, perms)
            } catch (_: Exception) {
            }
            executeGitCommand(
                listOf("git", "config", "credential.helper", helperScript.toString()),
                workingDir = repoDir,
            )
        }
    }

    private fun injectCredentials(repoUrl: String, connection: ConnectionDocument): String {
        if (!repoUrl.startsWith("https://") && !repoUrl.startsWith("http://")) return repoUrl

        // For bearer token (GitHub, GitLab OAuth): inject as username
        if (!connection.bearerToken.isNullOrBlank()) {
            return repoUrl.replace("://", "://oauth2:${connection.bearerToken}@")
        }

        // For basic auth
        if (!connection.username.isNullOrBlank() && !connection.password.isNullOrBlank()) {
            return repoUrl.replace("://", "://${connection.username}:${connection.password}@")
        }

        return repoUrl
    }

    fun detectDefaultBranch(repoDir: Path): String {
        // Try to detect from remote HEAD
        val result = executeGitCommand(
            listOf("git", "symbolic-ref", "refs/remotes/origin/HEAD", "--short"),
            workingDir = repoDir,
        )
        if (result.success) {
            val branch = result.output.trim().removePrefix("origin/")
            if (branch.isNotBlank()) return branch
        }

        // Fallback: check if main or master exists
        val branchResult = executeGitCommand(
            listOf("git", "branch", "-r"),
            workingDir = repoDir,
        )
        val branches = branchResult.output.lines().map { it.trim() }
        return when {
            branches.any { it == "origin/main" } -> "main"
            branches.any { it == "origin/master" } -> "master"
            else -> "main"
        }
    }

    fun listRemoteBranches(repoDir: Path): List<String> {
        val result = executeGitCommand(
            listOf("git", "branch", "-r", "--format", "%(refname:short)"),
            workingDir = repoDir,
        )
        return result.output.lines()
            .map { it.trim().removePrefix("origin/") }
            .filter { it.isNotBlank() && it != "HEAD" }
    }

    private fun checkoutBranch(repoDir: Path, branch: String) {
        executeGitCommand(listOf("git", "checkout", branch), workingDir = repoDir)
        executeGitCommand(listOf("git", "pull", "origin", branch, "--ff-only"), workingDir = repoDir)
    }

    /**
     * Parse git log and save new commits to MongoDB for the indexer to pick up.
     */
    private suspend fun discoverNewCommits(
        project: ProjectDocument,
        resource: ProjectResource,
        repoDir: Path,
        branch: String,
    ) {
        // Get last 50 commits on this branch
        val logResult = executeGitCommand(
            listOf(
                "git", "log", branch,
                "--format=%H|%an|%aI|%s",
                "-50",
            ),
            workingDir = repoDir,
        )

        if (!logResult.success) {
            logger.warn { "Git log failed for ${resource.resourceIdentifier}: ${logResult.output}" }
            return
        }

        val commits = logResult.output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 4)
                if (parts.size >= 4) {
                    GitCommitInfo(
                        commitHash = parts[0],
                        author = parts[1],
                        message = parts[3],
                        commitDate = runCatching { Instant.parse(parts[2]) }.getOrNull(),
                    )
                } else null
            }

        if (commits.isNotEmpty()) {
            commitStateManager.saveNewCommits(
                clientId = project.clientId.value,
                projectId = project.id.value,
                commits = commits,
                branch = branch,
            )
            logger.info { "Discovered ${commits.size} commits on $branch for ${resource.resourceIdentifier}" }
        }
    }

    /**
     * Structured file metadata for git structural ingest.
     */
    data class GitFileMetadata(
        val path: String,
        val extension: String,
        val language: String,
        val sizeBytes: Long,
    )

    /**
     * Structured branch metadata for git structural ingest.
     */
    data class GitBranchMetadata(
        val name: String,
        val isDefault: Boolean,
        val status: String = "active",
        val lastCommitHash: String = "",
    )

    /**
     * Get all tracked files with metadata for structural KB ingest.
     *
     * Returns structured file info (path, extension, language, size).
     * Excludes common non-source directories.
     */
    fun getFileListWithMetadata(repoDir: Path): List<GitFileMetadata> {
        val result = executeGitCommand(
            listOf("git", "ls-files"),
            workingDir = repoDir,
        )
        if (!result.success) return emptyList()

        val excludeDirs = setOf(
            "node_modules/", "build/", "target/", ".gradle/", ".idea/",
            "dist/", "out/", "__pycache__/", ".tox/", "vendor/", ".venv/",
        )

        return result.output.lines()
            .filter { it.isNotBlank() }
            .filter { path -> excludeDirs.none { path.startsWith(it) } }
            .mapNotNull { path ->
                val file = repoDir.resolve(path)
                val ext = path.substringAfterLast('.', "")
                val lang = extensionToLanguage(ext)
                val size = try { java.nio.file.Files.size(file) } catch (_: Exception) { 0L }
                GitFileMetadata(
                    path = path,
                    extension = ext,
                    language = lang,
                    sizeBytes = size,
                )
            }
    }

    /**
     * Get metadata for all remote branches.
     */
    fun getBranchMetadata(repoDir: Path, defaultBranch: String): List<GitBranchMetadata> {
        val branchNames = listRemoteBranches(repoDir)
        return branchNames.map { name ->
            val hashResult = executeGitCommand(
                listOf("git", "rev-parse", "origin/$name"),
                workingDir = repoDir,
            )
            GitBranchMetadata(
                name = name,
                isDefault = name == defaultBranch,
                status = "active",
                lastCommitHash = if (hashResult.success) hashResult.output.trim().take(40) else "",
            )
        }
    }

    /**
     * Read source file contents for tree-sitter parsing in KB service.
     *
     * Filters to source-code-only files (with recognized language),
     * sorts by size ascending (smallest first for broader coverage),
     * and enforces limits:
     * - Max 150 files
     * - Max 50KB per file
     * - Max ~5MB total payload
     */
    fun readSourceFileContents(
        files: List<GitFileMetadata>,
        repoDir: Path,
    ): List<Pair<String, String>> {
        val maxFiles = 150
        val maxFileBytes = 50 * 1024L       // 50KB per file
        val maxTotalBytes = 5 * 1024 * 1024L // 5MB total

        // Only source files with recognized language
        val sourceFiles = files
            .filter { it.language.isNotEmpty() }
            .filter { it.language !in setOf("json", "yaml", "xml", "markdown", "html", "css", "sql", "shell", "gradle", "protobuf") }
            .filter { it.sizeBytes in 1..maxFileBytes }
            .sortedBy { it.sizeBytes }
            .take(maxFiles)

        val result = mutableListOf<Pair<String, String>>()
        var totalBytes = 0L

        for (fileMeta in sourceFiles) {
            if (totalBytes >= maxTotalBytes) break
            try {
                val file = repoDir.resolve(fileMeta.path)
                if (!java.nio.file.Files.exists(file)) continue
                val size = java.nio.file.Files.size(file)
                if (size > maxFileBytes || size == 0L) continue

                val content = file.toFile().readText(Charsets.UTF_8)
                val truncated = if (content.length > maxFileBytes.toInt()) {
                    content.substring(0, maxFileBytes.toInt())
                } else {
                    content
                }

                totalBytes += truncated.length
                result.add(fileMeta.path to truncated)
            } catch (e: Exception) {
                // Skip files that can't be read (binary, encoding issues, etc.)
                continue
            }
        }

        return result
    }

    private fun extensionToLanguage(ext: String): String = when (ext.lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "ts", "tsx" -> "typescript"
        "js", "jsx" -> "javascript"
        "go" -> "go"
        "rs" -> "rust"
        "rb" -> "ruby"
        "cs" -> "csharp"
        "cpp", "cc", "cxx", "c" -> "c/c++"
        "swift" -> "swift"
        "scala" -> "scala"
        "groovy" -> "groovy"
        "sh", "bash" -> "shell"
        "sql" -> "sql"
        "html", "htm" -> "html"
        "css", "scss", "sass" -> "css"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "xml" -> "xml"
        "md" -> "markdown"
        "proto" -> "protobuf"
        "gradle" -> "gradle"
        else -> ""
    }

    /**
     * Get the file tree of the repository (for KB indexation).
     */
    fun getFileTree(repoDir: Path, maxDepth: Int = 4): String {
        val result = executeGitCommand(
            listOf("git", "ls-files"),
            workingDir = repoDir,
        )
        if (!result.success) return ""

        // Group by directory for structured output
        val files = result.output.lines().filter { it.isNotBlank() }
        val tree = StringBuilder()
        tree.appendLine("Repository file tree (${files.size} files):")

        // Group by top-level directory
        val grouped = files.groupBy { it.substringBefore("/", missingDelimiterValue = ".") }
        for ((dir, dirFiles) in grouped.toSortedMap()) {
            if (dir == ".") {
                dirFiles.forEach { tree.appendLine("  $it") }
            } else {
                tree.appendLine("  $dir/ (${dirFiles.size} files)")
                dirFiles.take(20).forEach { tree.appendLine("    $it") }
                if (dirFiles.size > 20) tree.appendLine("    ... and ${dirFiles.size - 20} more")
            }
        }
        return tree.toString()
    }

    /**
     * Read key documentation files from the repository.
     */
    fun readDocumentationFiles(repoDir: Path): Map<String, String> {
        val docFiles = listOf(
            "README.md", "README.rst", "README.txt", "README",
            "CONTRIBUTING.md", "CHANGELOG.md", "ARCHITECTURE.md",
            "docs/README.md", "doc/README.md",
        )

        val result = mutableMapOf<String, String>()
        for (docFile in docFiles) {
            val filePath = repoDir.resolve(docFile)
            if (filePath.exists()) {
                try {
                    val content = Files.readString(filePath)
                    if (content.length > 50) { // Skip trivially small files
                        result[docFile] = content.take(10_000) // Cap at 10K chars per file
                    }
                } catch (e: Exception) {
                    logger.debug { "Cannot read $docFile: ${e.message}" }
                }
            }
        }
        return result
    }

    /**
     * Get the diff for a specific commit.
     */
    fun getCommitDiff(repoDir: Path, commitHash: String): String {
        val result = executeGitCommand(
            listOf("git", "show", commitHash, "--stat", "--patch", "--no-color"),
            workingDir = repoDir,
        )
        return if (result.success) result.output.take(15_000) else "" // Cap diff at 15K
    }

    /**
     * Get summary of recent changes on a branch (for KB overview).
     */
    fun getRecentChangeSummary(repoDir: Path, branch: String, limit: Int = 10): String {
        val result = executeGitCommand(
            listOf(
                "git", "log", branch,
                "--format=- %h %s (%an, %ar)",
                "-$limit",
            ),
            workingDir = repoDir,
        )
        return if (result.success) result.output else ""
    }

    /**
     * Detect project language and build system from files.
     */
    fun detectProjectStack(repoDir: Path): String {
        val indicators = mutableListOf<String>()

        val fileChecks = mapOf(
            "build.gradle.kts" to "Kotlin/Gradle",
            "build.gradle" to "Java/Gradle",
            "pom.xml" to "Java/Maven",
            "package.json" to "JavaScript/Node.js",
            "Cargo.toml" to "Rust/Cargo",
            "go.mod" to "Go",
            "requirements.txt" to "Python",
            "pyproject.toml" to "Python",
            "Gemfile" to "Ruby",
            "composer.json" to "PHP",
            "Dockerfile" to "Docker",
            "docker-compose.yml" to "Docker Compose",
            ".github/workflows" to "GitHub Actions CI",
            ".gitlab-ci.yml" to "GitLab CI",
            "Jenkinsfile" to "Jenkins CI",
            "Makefile" to "Make",
        )

        for ((file, tech) in fileChecks) {
            if (repoDir.resolve(file).exists()) {
                indicators.add(tech)
            }
        }

        return if (indicators.isNotEmpty()) {
            "Tech stack: ${indicators.joinToString(", ")}"
        } else {
            "Tech stack: unknown"
        }
    }

    data class GitCommandResult(val success: Boolean, val output: String)

    private fun executeGitCommand(command: List<String>, workingDir: Path?): GitCommandResult {
        return try {
            val pb = ProcessBuilder(command)
            workingDir?.let { pb.directory(it.toFile()) }
            pb.redirectErrorStream(true)
            pb.environment()["GIT_TERMINAL_PROMPT"] = "0" // Never prompt for credentials

            val process = pb.start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val exitCode = process.waitFor()

            GitCommandResult(exitCode == 0, output)
        } catch (e: Exception) {
            GitCommandResult(false, e.message ?: "Unknown error")
        }
    }
}
