package com.jervis.rag._internal.repository

/**
 * Vector store abstraction - package-private.
 * Implementation uses Weaviate but interface allows future changes.
 */
internal interface VectorStore {
    /**
     * Store document with embedding.
     */
    suspend fun store(
        collection: VectorCollection,
        document: VectorDocument,
        classNameOverride: String? = null,
    ): Result<String>

    /**
     * Search for similar documents.
     */
    suspend fun search(
        collection: VectorCollection,
        query: VectorQuery,
        classNameOverride: String? = null,
    ): Result<List<VectorSearchResult>>

    /**
     * Get document by ID.
     */
    suspend fun getById(
        collection: VectorCollection,
        id: String,
        classNameOverride: String? = null,
    ): Result<VectorSearchResult?>

    /**
     * Delete document by ID.
     */
    suspend fun delete(
        collection: VectorCollection,
        id: String,
        classNameOverride: String? = null,
    ): Result<Unit>

    /**
     * Delete all documents matching filter.
     */
    suspend fun deleteByFilter(
        collection: VectorCollection,
        filters: VectorFilters,
        classNameOverride: String? = null,
    ): Result<Int>
}
