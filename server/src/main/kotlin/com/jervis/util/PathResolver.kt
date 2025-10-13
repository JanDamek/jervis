package com.jervis.util

import com.jervis.configuration.DataRootProperties
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class PathResolver(
    private val dataRoot: DataRootProperties,
) {
    private val root: Path = Path.of(dataRoot.rootDir)

    fun rootDir(): Path = root

    fun clientDir(clientId: ObjectId): Path =
        root.resolve("clients").resolve(clientId.toHexString())

    fun clientAudioDir(client: ClientDocument): Path =
        clientDir(client.id).resolve("audio")

    fun projectDir(clientId: ObjectId, projectId: ObjectId): Path =
        clientDir(clientId).resolve("projects").resolve(projectId.toHexString())

    fun projectDir(project: ProjectDocument): Path = projectDir(project.clientId, project.id)

    fun projectGitDir(project: ProjectDocument): Path = projectDir(project).resolve("git")

    fun projectUploadsDir(project: ProjectDocument): Path = projectDir(project).resolve("uploads")

    fun projectAudioDir(project: ProjectDocument): Path = projectDir(project).resolve("audio")
}
