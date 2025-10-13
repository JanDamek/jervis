package com.jervis.service

import org.bson.types.ObjectId
import java.nio.file.Path

/**
 * Service for managing the directory structure hierarchy.
 * Structure: {rootDir}/{clientId}/{projectId}/{subdirectory}
 * Subdirectories: git, upload, meetings
 */
interface IDirectoryStructureService {
    /**
     * Ensures the complete directory structure exists for a given client and project.
     * Creates all necessary directories if they don't exist.
     */
    suspend fun ensureProjectDirectories(
        clientId: ObjectId,
        projectId: ObjectId,
    )

    /**
     * Ensures only the client directory exists.
     */
    suspend fun ensureClientDirectory(clientId: ObjectId)

    /**
     * Gets the path to the client directory.
     */
    fun getClientDirectory(clientId: ObjectId): Path

    /**
     * Gets the path to the project directory.
     */
    fun getProjectDirectory(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path

    /**
     * Gets the path to the git subdirectory for a project.
     */
    fun getGitDirectory(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path

    /**
     * Gets the path to the upload subdirectory for a project.
     */
    fun getUploadDirectory(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path

    /**
     * Gets the path to the meetings subdirectory for a project.
     */
    fun getMeetingsDirectory(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Path

    /**
     * Resolves a relative path within a project's subdirectory.
     */
    fun resolveProjectPath(
        clientId: ObjectId,
        projectId: ObjectId,
        subdirectory: String,
        relativePath: String,
    ): Path
}
