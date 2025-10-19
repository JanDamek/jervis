package com.jervis.service.storage

import com.jervis.configuration.DataRootProperties
import com.jervis.domain.storage.DirectoryStructure
import com.jervis.domain.storage.ProjectSubdirectory
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import kotlinx.coroutines.Dispatchers
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
 *     - projects/{projectId}/
 *       - git/
 *       - uploads/
 *       - audio/
 *       - documents/
 *       - meetings/
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
    private val workspaceRoot: Path = Paths.get(dataRootProperties.rootDir).toAbsolutePath()

    init {
        logger.info("Workspace root initialized at: $workspaceRoot")
    }

    suspend fun ensureWorkspaceStructure() {
        withContext(Dispatchers.IO) {
            val structure = DirectoryStructure.forWorkspace(dataRootProperties.rootDir)

            createDirectoryIfNotExists(structure.clientsRoot)
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

    fun clientsRoot(): Path = workspaceRoot.resolve(DirectoryStructure.CLIENTS_DIR)

    fun tmpRoot(): Path = workspaceRoot.resolve(DirectoryStructure.TMP_DIR)

    fun tmpScrapingDir(): Path = tmpRoot().resolve(DirectoryStructure.SCRAPING_SUBDIR)

    fun tmpProcessingDir(): Path = tmpRoot().resolve(DirectoryStructure.PROCESSING_SUBDIR)

    fun storageRoot(): Path = workspaceRoot.resolve(DirectoryStructure.STORAGE_DIR)

    fun cacheRoot(): Path = workspaceRoot.resolve(DirectoryStructure.CACHE_DIR)

    fun clientDir(clientId: ObjectId): Path = clientsRoot().resolve(clientId.toHexString())

    fun clientDir(client: ClientDocument): Path = clientDir(client.id)

    fun clientAudioDir(clientId: ObjectId): Path = clientDir(clientId).resolve(DirectoryStructure.AUDIO_SUBDIR)

    fun clientAudioDir(client: ClientDocument): Path = clientAudioDir(client.id)

    fun clientProjectsRoot(clientId: ObjectId): Path = clientDir(clientId).resolve(DirectoryStructure.PROJECTS_SUBDIR)

    fun projectDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = clientProjectsRoot(clientId).resolve(projectId.toHexString())

    fun projectDir(project: ProjectDocument): Path = projectDir(project.clientId, project.id)

    fun projectGitDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = projectDir(clientId, projectId).resolve(DirectoryStructure.GIT_SUBDIR)

    fun projectGitDir(project: ProjectDocument): Path = projectGitDir(project.clientId, project.id)

    fun projectUploadsDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = projectDir(clientId, projectId).resolve(DirectoryStructure.UPLOADS_SUBDIR)

    fun projectUploadsDir(project: ProjectDocument): Path = projectUploadsDir(project.clientId, project.id)

    fun projectAudioDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = projectDir(clientId, projectId).resolve(DirectoryStructure.AUDIO_SUBDIR)

    fun projectAudioDir(project: ProjectDocument): Path = projectAudioDir(project.clientId, project.id)

    fun projectDocumentsDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = projectDir(clientId, projectId).resolve(DirectoryStructure.DOCUMENTS_SUBDIR)

    fun projectDocumentsDir(project: ProjectDocument): Path = projectDocumentsDir(project.clientId, project.id)

    fun projectMeetingsDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = projectDir(clientId, projectId).resolve(DirectoryStructure.MEETINGS_SUBDIR)

    fun projectMeetingsDir(project: ProjectDocument): Path = projectMeetingsDir(project.clientId, project.id)

    fun resolveProjectPath(
        clientId: ObjectId,
        projectId: ObjectId,
        subdirectory: ProjectSubdirectory,
        relativePath: String,
    ): Path = projectDir(clientId, projectId).resolve(subdirectory.dirName).resolve(relativePath)

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
}
