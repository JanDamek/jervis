package com.jervis.service.storage

import com.jervis.configuration.properties.DataRootProperties
import com.jervis.domain.storage.DirectoryStructure
import com.jervis.domain.storage.ProjectSubdirectory
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * Service for managing the complete workspace directory structure hierarchy.
 * All file operations in the server should use this service for path resolution.
 *
 * Workspace structure: {workspaceRoot}/
 *   - clients/{clientId}/
 *     - audio/
 *     - mono-repos/{monoRepoId}/
 *       - git/
 *     - projects/{projectId}/
 *       - git/
 *       - uploads/
 *       - audio/
 *       - documents/
 *       - meetings/
 *   - keys/
 *     - ssh/
 *     - gpg/
 *   - tmp/
 *     - scraping/
 *     - processing/
 *   - storage/
 *   - cache/
 */
@Service
class DirectoryStructureService(
    private val dataRootProperties: DataRootProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val workspaceRoot: Path = expandPath(dataRootProperties.rootDir)

    init {
        logger.info("Workspace root initialized at: $workspaceRoot")
    }

    @PostConstruct
    fun initializeWorkspaceOnStartup() {
        runBlocking {
            ensureWorkspaceStructure()
            ensureAllExistingProjectDirectories()
        }
    }

    private suspend fun ensureAllExistingProjectDirectories() {
        logger.info("Ensuring all existing project directories are created...")
    }

    suspend fun ensureWorkspaceStructure() {
        withContext(Dispatchers.IO) {
            val structure = DirectoryStructure.forWorkspace(dataRootProperties.rootDir)

            createDirectoryIfNotExists(structure.clientsRoot)
            createDirectoryIfNotExists(structure.keysRoot)
            createDirectoryIfNotExists(structure.sshKeysDir)
            createDirectoryIfNotExists(structure.gpgKeysDir)
            createDirectoryIfNotExists(structure.tmpRoot)
            createDirectoryIfNotExists(structure.tmpScrapingDir)
            createDirectoryIfNotExists(structure.tmpProcessingDir)
            createDirectoryIfNotExists(structure.storageRoot)
            createDirectoryIfNotExists(structure.cacheRoot)

            logger.info("Ensured base workspace structure at $workspaceRoot")
        }
    }

    suspend fun ensureClientDirectories(clientId: ObjectId) {
        withContext(Dispatchers.IO) {
            val structure = DirectoryStructure.forClient(dataRootProperties.rootDir, clientId)

            structure.clientDir?.let { createDirectoryIfNotExists(it) }
            structure.clientAudioDir?.let { createDirectoryIfNotExists(it) }
            structure.clientProjectsRoot?.let { createDirectoryIfNotExists(it) }

            logger.info("Ensured client directories for client=${clientId.toHexString()}")
        }
    }

    suspend fun ensureProjectDirectories(
        clientId: ObjectId,
        projectId: ObjectId,
    ) {
        withContext(Dispatchers.IO) {
            val structure = DirectoryStructure.forProject(dataRootProperties.rootDir, clientId, projectId)

            structure.clientDir?.let { createDirectoryIfNotExists(it) }
            structure.clientProjectsRoot?.let { createDirectoryIfNotExists(it) }
            structure.projectDir?.let { createDirectoryIfNotExists(it) }
            structure.projectGitDir?.let { createDirectoryIfNotExists(it) }
            structure.projectUploadsDir?.let { createDirectoryIfNotExists(it) }
            structure.projectAudioDir?.let { createDirectoryIfNotExists(it) }
            structure.projectDocumentsDir?.let { createDirectoryIfNotExists(it) }
            structure.projectMeetingsDir?.let { createDirectoryIfNotExists(it) }

            logger.info(
                "Ensured project directories for client=${clientId.toHexString()}, project=${projectId.toHexString()}",
            )
        }
    }

    fun workspaceRoot(): Path = workspaceRoot

    fun clientsRoot(): Path =
        workspaceRoot.resolve(DirectoryStructure.CLIENTS_DIR).also {
            createDirectoryIfNotExists(it)
        }

    fun tmpRoot(): Path =
        workspaceRoot.resolve(DirectoryStructure.TMP_DIR).also {
            createDirectoryIfNotExists(it)
        }

    fun tmpScrapingDir(): Path =
        tmpRoot().resolve(DirectoryStructure.SCRAPING_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun tmpProcessingDir(): Path =
        tmpRoot().resolve(DirectoryStructure.PROCESSING_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun tempDir(): Path = tmpRoot()

    fun storageRoot(): Path =
        workspaceRoot.resolve(DirectoryStructure.STORAGE_DIR).also {
            createDirectoryIfNotExists(it)
        }

    fun cacheRoot(): Path =
        workspaceRoot.resolve(DirectoryStructure.CACHE_DIR).also {
            createDirectoryIfNotExists(it)
        }

    fun keysRoot(): Path =
        workspaceRoot.resolve(DirectoryStructure.KEYS_DIR).also {
            createDirectoryIfNotExists(it)
        }

    fun sshKeysDir(): Path =
        keysRoot().resolve(DirectoryStructure.SSH_KEYS_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun gpgKeysDir(): Path =
        keysRoot().resolve(DirectoryStructure.GPG_KEYS_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun projectSshKeyDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path =
        sshKeysDir().resolve(clientId.toHexString()).resolve(projectId.toHexString()).also {
            createDirectoryIfNotExists(it)
        }

    fun projectSshKeyDir(project: ProjectDocument): Path = projectSshKeyDir(project.clientId, project.id)

    fun clientDir(clientId: ObjectId): Path =
        clientsRoot().resolve(clientId.toHexString()).also {
            createDirectoryIfNotExists(it)
        }

    fun clientDir(client: ClientDocument): Path = clientDir(client.id)

    fun clientAudioDir(clientId: ObjectId): Path =
        clientDir(clientId).resolve(DirectoryStructure.AUDIO_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun clientAudioDir(client: ClientDocument): Path = clientAudioDir(client.id)

    fun clientGitDir(clientId: ObjectId): Path =
        clientDir(clientId).resolve("git").also {
            createDirectoryIfNotExists(it)
        }

    fun clientMonoReposRoot(clientId: ObjectId): Path =
        clientDir(clientId).resolve("mono-repos").also {
            createDirectoryIfNotExists(it)
        }

    fun clientMonoRepoDir(
        clientId: ObjectId,
        monoRepoId: String,
    ): Path =
        clientMonoReposRoot(clientId).resolve(monoRepoId).also {
            createDirectoryIfNotExists(it)
        }

    fun clientMonoRepoGitDir(
        clientId: ObjectId,
        monoRepoId: String,
    ): Path =
        clientMonoRepoDir(clientId, monoRepoId).resolve("git").also {
            createDirectoryIfNotExists(it)
        }

    fun clientProjectsRoot(clientId: ObjectId): Path =
        clientDir(clientId).resolve(DirectoryStructure.PROJECTS_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun projectDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path =
        clientProjectsRoot(clientId).resolve(projectId.toHexString()).also {
            createDirectoryIfNotExists(it)
        }

    fun projectDir(project: ProjectDocument): Path = projectDir(project.clientId, project.id)

    fun projectGitDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path =
        projectDir(clientId, projectId).resolve(DirectoryStructure.GIT_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun projectGitDir(project: ProjectDocument): Path = projectGitDir(project.clientId, project.id)

    fun projectUploadsDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path =
        projectDir(clientId, projectId).resolve(DirectoryStructure.UPLOADS_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun projectUploadsDir(project: ProjectDocument): Path = projectUploadsDir(project.clientId, project.id)

    fun projectAudioDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path =
        projectDir(clientId, projectId).resolve(DirectoryStructure.AUDIO_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun projectAudioDir(project: ProjectDocument): Path = projectAudioDir(project.clientId, project.id)

    fun projectDocumentsDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path =
        projectDir(clientId, projectId).resolve(DirectoryStructure.DOCUMENTS_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun projectDocumentsDir(project: ProjectDocument): Path = projectDocumentsDir(project.clientId, project.id)

    fun projectMeetingsDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path =
        projectDir(clientId, projectId).resolve(DirectoryStructure.MEETINGS_SUBDIR).also {
            createDirectoryIfNotExists(it)
        }

    fun projectMeetingsDir(project: ProjectDocument): Path = projectMeetingsDir(project.clientId, project.id)

    fun resolveProjectPath(
        clientId: ObjectId,
        projectId: ObjectId,
        subdirectory: ProjectSubdirectory,
        relativePath: String,
    ): Path = projectDir(clientId, projectId).resolve(subdirectory.dirName).resolve(relativePath)

    /**
     * Resolve an existing file path within a project's workspace using the centralized directory structure.
     *
     * Behavior:
     * - If [givenPath] is absolute and exists, returns it as-is.
     * - If [givenPath] is relative to workspace root (starts with clients/..), resolves from [workspaceRoot].
     * - If [givenPath] starts with a known project subdirectory (git/documents/meetings/uploads/audio), it is resolved under the project's root.
     * - Otherwise, it will try to locate the file under the project's preferred subdirectory (if provided),
     *   then under each known subdirectory, and finally directly under the project directory.
     *
     * Fails fast: throws [IllegalStateException] if the file cannot be found.
     */
    fun resolveExistingProjectPath(
        clientId: ObjectId,
        projectId: ObjectId,
        givenPath: String,
        preferred: ProjectSubdirectory? = null,
    ): Path {
        val raw = givenPath.trim().trim('"', '\'', '`')
        if (raw.isEmpty()) error("Empty path provided")

        val asPath = Paths.get(raw)
        if (asPath.isAbsolute && Files.exists(asPath)) return asPath

        val cleaned = raw.trimStart('/', '\\')

        // If path is already relative to workspace (clients/...)
        if (cleaned.startsWith(DirectoryStructure.CLIENTS_DIR + "/")) {
            val p = workspaceRoot.resolve(cleaned).normalize()
            if (Files.exists(p)) return p
        }

        val projectBase = projectDir(clientId, projectId)

        // If path already starts with a known subdirectory, try directly under project
        ProjectSubdirectory.entries.firstOrNull { cleaned.startsWith(it.dirName + "/") }?.let {
            val p = projectBase.resolve(cleaned).normalize()
            if (Files.exists(p)) return p
        }

        val tried = mutableListOf<Path>()

        fun candidate(p: Path): Path {
            tried.add(p)
            return p
        }

        // Preferred subdir first (if any)
        val subdirs: List<ProjectSubdirectory> =
            (preferred?.let { listOf(it) } ?: emptyList()) + ProjectSubdirectory.entries.filter { it != preferred }

        // 1) Under preferred/each known subdir
        for (sd in subdirs) {
            val p = candidate(projectBase.resolve(sd.dirName).resolve(cleaned).normalize())
            if (Files.exists(p)) return p
        }

        // 2) Directly under project directory
        run {
            val p = candidate(projectBase.resolve(cleaned).normalize())
            if (Files.exists(p)) return p
        }

        val attempts = tried.joinToString(" | ") { it.toString() }
        throw IllegalStateException("File not found for provided path: $givenPath (tried: $attempts)")
    }

    fun resolveExistingProjectPath(project: ProjectDocument, givenPath: String, preferred: ProjectSubdirectory? = null): Path =
        resolveExistingProjectPath(project.clientId, project.id, givenPath, preferred)

    fun resolveTmpScrapingPath(fileName: String): Path = tmpScrapingDir().resolve(fileName)

    fun resolveTmpProcessingPath(fileName: String): Path = tmpProcessingDir().resolve(fileName)

    fun resolveStoragePath(relativePath: String): Path = storageRoot().resolve(relativePath)

    fun resolveCachePath(relativePath: String): Path = cacheRoot().resolve(relativePath)

    suspend fun createTempFileInScraping(
        prefix: String,
        suffix: String,
    ): Path =
        withContext(Dispatchers.IO) {
            ensureDirectoryExists(tmpScrapingDir())
            Files.createTempFile(tmpScrapingDir(), prefix, suffix).also {
                logger.debug("Created temporary file for scraping: {}", it)
            }
        }

    suspend fun createTempFileInProcessing(
        prefix: String,
        suffix: String,
    ): Path =
        withContext(Dispatchers.IO) {
            ensureDirectoryExists(tmpProcessingDir())
            Files.createTempFile(tmpProcessingDir(), prefix, suffix).also {
                logger.debug("Created temporary file for processing: {}", it)
            }
        }

    suspend fun ensureDirectoryExists(path: Path) {
        withContext(Dispatchers.IO) {
            createDirectoryIfNotExists(path)
        }
    }

    fun exists(path: Path): Boolean = path.exists()

    private fun createDirectoryIfNotExists(path: Path) {
        if (!Files.exists(path)) {
            Files.createDirectories(path)
            logger.debug("Created directory: {}", path)
        }
    }

    private fun expandPath(pathString: String): Path {
        val expanded =
            when {
                pathString.startsWith("~/") -> {
                    val homeDir = System.getProperty("user.home")
                    pathString.replaceFirst("~", homeDir)
                }

                pathString.startsWith("~") && pathString.length > 1 -> {
                    pathString
                }

                else -> pathString
            }
        return Paths.get(expanded).toAbsolutePath().normalize()
    }
}
