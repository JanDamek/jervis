package com.jervis.service.storage

import com.jervis.configuration.DataRootProperties
import com.jervis.domain.storage.DirectoryStructure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Service for managing the directory structure hierarchy (server-internal, domain-first).
 */
@Service
class DirectoryStructureService(
    private val dataRootProperties: DataRootProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val rootPath: Path = Paths.get(dataRootProperties.rootDir)

    suspend fun ensureProjectDirectories(
        clientId: ObjectId,
        projectId: ObjectId,
    ) {
        withContext(Dispatchers.IO) {
            val structure = DirectoryStructure.forProject(dataRootProperties.rootDir, clientId, projectId)

            createDirectoryIfNotExists(structure.clientDir)
            createDirectoryIfNotExists(structure.projectDir)
            createDirectoryIfNotExists(structure.gitDir)
            createDirectoryIfNotExists(structure.uploadDir)
            createDirectoryIfNotExists(structure.meetingsDir)

            logger.info(
                "Ensured directory structure for client={}, project={}",
                clientId.toHexString(),
                projectId.toHexString(),
            )
        }
    }

    suspend fun ensureClientDirectory(clientId: ObjectId) {
        withContext(Dispatchers.IO) {
            val clientDir = rootPath.resolve(clientId.toHexString())
            createDirectoryIfNotExists(clientDir)

            logger.info("Ensured client directory for client={}", clientId.toHexString())
        }
    }

    fun getClientDirectory(clientId: ObjectId): Path = rootPath.resolve(clientId.toHexString())

    fun getProjectDirectory(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = getClientDirectory(clientId).resolve(projectId.toHexString())

    fun getGitDirectory(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = getProjectDirectory(clientId, projectId).resolve(DirectoryStructure.GIT_SUBDIR)

    fun getUploadDirectory(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = getProjectDirectory(clientId, projectId).resolve(DirectoryStructure.UPLOAD_SUBDIR)

    fun getMeetingsDirectory(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = getProjectDirectory(clientId, projectId).resolve(DirectoryStructure.MEETINGS_SUBDIR)

    fun resolveProjectPath(
        clientId: ObjectId,
        projectId: ObjectId,
        subdirectory: String,
        relativePath: String,
    ): Path = getProjectDirectory(clientId, projectId).resolve(subdirectory).resolve(relativePath)

    private fun createDirectoryIfNotExists(path: Path) {
        if (!Files.exists(path)) {
            Files.createDirectories(path)
            logger.debug("Created directory: {}", path)
        }
    }
}
