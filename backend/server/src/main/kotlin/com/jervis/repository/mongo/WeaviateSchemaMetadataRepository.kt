package com.jervis.repository.mongo

import com.jervis.entity.WeaviateSchemaMetadata
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for tracking Weaviate schema configuration versions.
 * Used to detect schema changes and trigger automatic migration.
 */
@Repository
interface WeaviateSchemaMetadataRepository : CoroutineCrudRepository<WeaviateSchemaMetadata, ObjectId> {
    /**
     * Find the latest schema metadata by migration timestamp.
     * Returns the most recent schema configuration.
     */
    suspend fun findFirstByOrderByMigratedAtDesc(): WeaviateSchemaMetadata?

    /**
     * Find schema metadata by version.
     * Used to check if specific version was previously applied.
     */
    suspend fun findBySchemaVersion(schemaVersion: String): WeaviateSchemaMetadata?
}
