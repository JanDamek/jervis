package com.jervis.project

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.project.ProjectDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for project documents.
 * NOTE: Do NOT use the inherited findById() — it causes AopInvocationException at runtime
 * with Kotlin inline value class IDs. Use getById() defined here instead.
 */
@Repository
interface ProjectRepository : CoroutineCrudRepository<ProjectDocument, ProjectId> {
    /**
     * Find project by ID. Use this instead of the inherited findById(ProjectId) to avoid
     * AOP proxy issues with Kotlin inline value classes.
     * Spring Data derives the query from the method name — no @Query needed.
     */
    suspend fun getById(id: ProjectId): ProjectDocument?

    /**
     * Find all projects for a given client.
     */
    fun findByClientId(clientId: ClientId): Flow<ProjectDocument>

    /**
     * Find all projects that reference a connection in their resources list.
     */
    @Query("{ 'resources.connectionId': ?0 }")
    fun findByResourcesConnectionId(connectionId: ObjectId): Flow<ProjectDocument>

    /**
     * Find the JERVIS internal project for a client (max 1 per client).
     */
    suspend fun findByClientIdAndIsJervisInternal(clientId: ClientId, isJervisInternal: Boolean): ProjectDocument?

    /**
     * Find all projects belonging to a group. Used for KB cross-project visibility.
     */
    fun findByGroupId(groupId: ProjectGroupId): Flow<ProjectDocument>

    /**
     * Find projects with pending KB retag-group operations (crash recovery).
     */
    fun findByPendingRetagGroupIdIsNotNull(): Flow<ProjectDocument>

    // --- Active-only queries for background processing ---

    /** Active projects for a client (excludes closed/completed projects). */
    fun findByClientIdAndActiveTrue(clientId: ClientId): Flow<ProjectDocument>

    /** Active projects referencing a connection (for polling). */
    @Query("{ 'resources.connectionId': ?0, 'active': true }")
    fun findByResourcesConnectionIdAndActiveTrue(connectionId: ObjectId): Flow<ProjectDocument>

    /** All active projects (for background indexing, git sync, etc.). */
    fun findByActiveTrue(): Flow<ProjectDocument>

    /** Active projects in a group (for KB cross-project visibility). */
    fun findByGroupIdAndActiveTrue(groupId: ProjectGroupId): Flow<ProjectDocument>
}
