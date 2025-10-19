package com.jervis.util

import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.storage.DirectoryStructureService
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Legacy path resolver - deprecated in favor of DirectoryStructureService.
 * Delegates all calls to DirectoryStructureService for unified workspace management.
 *
 * @deprecated Use DirectoryStructureService directly instead.
 */
@Deprecated(
    message = "Use DirectoryStructureService directly for all path operations",
    replaceWith = ReplaceWith("DirectoryStructureService", "com.jervis.service.storage.DirectoryStructureService"),
)
@Component
class PathResolver(
    private val directoryStructureService: DirectoryStructureService,
) {
    fun rootDir(): Path = directoryStructureService.workspaceRoot()

    fun clientDir(clientId: ObjectId): Path = directoryStructureService.clientDir(clientId)

    fun clientAudioDir(client: ClientDocument): Path = directoryStructureService.clientAudioDir(client)

    fun projectDir(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path = directoryStructureService.projectDir(clientId, projectId)

    fun projectDir(project: ProjectDocument): Path = directoryStructureService.projectDir(project)

    fun projectGitDir(project: ProjectDocument): Path = directoryStructureService.projectGitDir(project)

    fun projectUploadsDir(project: ProjectDocument): Path = directoryStructureService.projectUploadsDir(project)

    fun projectAudioDir(project: ProjectDocument): Path = directoryStructureService.projectAudioDir(project)
}
