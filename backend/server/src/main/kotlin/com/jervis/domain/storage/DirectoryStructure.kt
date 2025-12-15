package com.jervis.domain.storage

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
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
 *   - keys/
 *     - ssh/
 *     - gpg/
 *   - tmp/
 *     - scraping/
 *     - processing/
 *   - storage/
 *   - cache/
 */
data class DirectoryStructure(
    val workspaceRoot: Path,
    val clientId: ClientId?,
    val projectId: ProjectId?,
) {
    val clientsRoot: Path = workspaceRoot.resolve(CLIENTS_DIR)
    val keysRoot: Path = workspaceRoot.resolve(KEYS_DIR)
    val tmpRoot: Path = workspaceRoot.resolve(TMP_DIR)
    val storageRoot: Path = workspaceRoot.resolve(STORAGE_DIR)
    val cacheRoot: Path = workspaceRoot.resolve(CACHE_DIR)

    val sshKeysDir: Path = keysRoot.resolve(SSH_KEYS_SUBDIR)
    val gpgKeysDir: Path = keysRoot.resolve(GPG_KEYS_SUBDIR)

    val tmpScrapingDir: Path = tmpRoot.resolve(SCRAPING_SUBDIR)
    val tmpProcessingDir: Path = tmpRoot.resolve(PROCESSING_SUBDIR)

    val clientDir: Path? = clientId?.let { clientsRoot.resolve(it.toString()) }
    val clientAudioDir: Path? = clientDir?.resolve(AUDIO_SUBDIR)
    val clientProjectsRoot: Path? = clientDir?.resolve(PROJECTS_SUBDIR)

    val projectDir: Path? = projectId?.let { clientProjectsRoot?.resolve(it.toString()) }
    val projectGitDir: Path? = projectDir?.resolve(GIT_SUBDIR)
    val projectUploadsDir: Path? = projectDir?.resolve(UPLOADS_SUBDIR)
    val projectAudioDir: Path? = projectDir?.resolve(AUDIO_SUBDIR)
    val projectDocumentsDir: Path? = projectDir?.resolve(DOCUMENTS_SUBDIR)
    val projectMeetingsDir: Path? = projectDir?.resolve(MEETINGS_SUBDIR)

    companion object {
        const val CLIENTS_DIR = "clients"
        const val KEYS_DIR = "keys"
        const val TMP_DIR = "tmp"
        const val STORAGE_DIR = "storage"
        const val CACHE_DIR = "cache"

        const val SSH_KEYS_SUBDIR = "ssh"
        const val GPG_KEYS_SUBDIR = "gpg"
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
            clientId: ClientId,
        ): DirectoryStructure = DirectoryStructure(Paths.get(workspaceRoot), clientId, null)

        fun forProject(
            workspaceRoot: String,
            clientId: ClientId,
            projectId: ProjectId,
        ): DirectoryStructure = DirectoryStructure(Paths.get(workspaceRoot), clientId, projectId)
    }
}
