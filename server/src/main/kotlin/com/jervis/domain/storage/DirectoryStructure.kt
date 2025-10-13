package com.jervis.domain.storage

import org.bson.types.ObjectId
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Represents the directory structure for a client-project hierarchy.
 * Structure: {rootDir}/{clientId}/{projectId}/{subdirectory}
 */
data class DirectoryStructure(
    val rootDir: Path,
    val clientId: ObjectId,
    val projectId: ObjectId,
) {
    val clientDir: Path = rootDir.resolve(clientId.toHexString())
    val projectDir: Path = clientDir.resolve(projectId.toHexString())
    val gitDir: Path = projectDir.resolve(GIT_SUBDIR)
    val uploadDir: Path = projectDir.resolve(UPLOAD_SUBDIR)
    val meetingsDir: Path = projectDir.resolve(MEETINGS_SUBDIR)

    companion object {
        const val GIT_SUBDIR = "git"
        const val UPLOAD_SUBDIR = "upload"
        const val MEETINGS_SUBDIR = "meetings"

        fun forProject(
            rootDir: String,
            clientId: ObjectId,
            projectId: ObjectId,
        ): DirectoryStructure = DirectoryStructure(Paths.get(rootDir), clientId, projectId)
    }
}

/**
 * Represents types of subdirectories within a project
 */
enum class ProjectSubdirectory(
    val dirName: String,
) {
    GIT("git"),
    UPLOAD("upload"),
    MEETINGS("meetings"),
}
