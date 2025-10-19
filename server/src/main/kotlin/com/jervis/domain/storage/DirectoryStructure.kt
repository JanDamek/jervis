package com.jervis.domain.storage

import org.bson.types.ObjectId
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Represents the complete directory structure for workspace management.
 * Root structure: {workspaceRoot}/
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
data class DirectoryStructure(
    val workspaceRoot: Path,
    val clientId: ObjectId?,
    val projectId: ObjectId?,
) {
    val clientsRoot: Path = workspaceRoot.resolve(CLIENTS_DIR)
    val tmpRoot: Path = workspaceRoot.resolve(TMP_DIR)
    val storageRoot: Path = workspaceRoot.resolve(STORAGE_DIR)
    val cacheRoot: Path = workspaceRoot.resolve(CACHE_DIR)

    val tmpScrapingDir: Path = tmpRoot.resolve(SCRAPING_SUBDIR)
    val tmpProcessingDir: Path = tmpRoot.resolve(PROCESSING_SUBDIR)

    val clientDir: Path? = clientId?.let { clientsRoot.resolve(it.toHexString()) }
    val clientAudioDir: Path? = clientDir?.resolve(AUDIO_SUBDIR)
    val clientProjectsRoot: Path? = clientDir?.resolve(PROJECTS_SUBDIR)

    val projectDir: Path? = projectId?.let { clientProjectsRoot?.resolve(it.toHexString()) }
    val projectGitDir: Path? = projectDir?.resolve(GIT_SUBDIR)
    val projectUploadsDir: Path? = projectDir?.resolve(UPLOADS_SUBDIR)
    val projectAudioDir: Path? = projectDir?.resolve(AUDIO_SUBDIR)
    val projectDocumentsDir: Path? = projectDir?.resolve(DOCUMENTS_SUBDIR)
    val projectMeetingsDir: Path? = projectDir?.resolve(MEETINGS_SUBDIR)

    companion object {
        const val CLIENTS_DIR = "clients"
        const val TMP_DIR = "tmp"
        const val STORAGE_DIR = "storage"
        const val CACHE_DIR = "cache"

        const val SCRAPING_SUBDIR = "scraping"
        const val PROCESSING_SUBDIR = "processing"
        const val AUDIO_SUBDIR = "audio"
        const val PROJECTS_SUBDIR = "projects"

        const val GIT_SUBDIR = "git"
        const val UPLOADS_SUBDIR = "uploads"
        const val DOCUMENTS_SUBDIR = "documents"
        const val MEETINGS_SUBDIR = "meetings"

        fun forWorkspace(workspaceRoot: String): DirectoryStructure = DirectoryStructure(Paths.get(workspaceRoot), null, null)

        fun forClient(
            workspaceRoot: String,
            clientId: ObjectId,
        ): DirectoryStructure = DirectoryStructure(Paths.get(workspaceRoot), clientId, null)

        fun forProject(
            workspaceRoot: String,
            clientId: ObjectId,
            projectId: ObjectId,
        ): DirectoryStructure = DirectoryStructure(Paths.get(workspaceRoot), clientId, projectId)
    }
}

/**
 * Represents types of subdirectories within a project
 */
enum class ProjectSubdirectory(
    val dirName: String,
) {
    GIT("git"),
    UPLOADS("uploads"),
    AUDIO("audio"),
    DOCUMENTS("documents"),
    MEETINGS("meetings"),
}

/**
 * Represents types of temporary directories
 */
enum class TmpSubdirectory(
    val dirName: String,
) {
    SCRAPING("scraping"),
    PROCESSING("processing"),
}
