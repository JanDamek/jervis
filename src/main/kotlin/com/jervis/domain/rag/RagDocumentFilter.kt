package com.jervis.domain.rag

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Filter for RagDocument search operations.
 * Supports filtering by project, document type, source type, creation time, and score.
 */
data class RagDocumentFilter(
    val projectId: ObjectId? = null,
    val documentType: RagDocumentType? = null,
    val ragSourceType: RagSourceType? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val minScore: Float? = null,
    val maxScore: Float? = null,
) {
    /**
     * Convert filter to Map for Qdrant database filtering.
     * Only includes basic filters that can be efficiently processed by Qdrant.
     */
    fun toMap(): Map<String, Any> {
        val filterMap = mutableMapOf<String, Any>()
        
        projectId?.let { filterMap["project"] = it }
        documentType?.let { filterMap["documentType"] = it.name }
        ragSourceType?.let { filterMap["ragSourceType"] = it.name }
        
        return filterMap
    }
    
    /**
     * Check if a document matches this filter.
     * Used for application-level filtering after Qdrant search.
     */
    fun matches(document: RagDocument, score: Float? = null): Boolean {
        // Check project ID
        if (projectId != null && document.projectId != projectId) {
            return false
        }
        
        // Check document type
        if (documentType != null && document.documentType != documentType) {
            return false
        }
        
        // Check source type
        if (ragSourceType != null && document.ragSourceType != ragSourceType) {
            return false
        }
        
        // Check creation time
        if (createdAfter != null && document.createdAt.isBefore(createdAfter)) {
            return false
        }
        
        if (createdBefore != null && document.createdAt.isAfter(createdBefore)) {
            return false
        }
        
        // Check score if provided
        if (score != null) {
            if (minScore != null && score < minScore) {
                return false
            }
            
            if (maxScore != null && score > maxScore) {
                return false
            }
        }
        
        return true
    }
    
    companion object {
        /**
         * Create a filter for documents from a specific time period.
         */
        fun fromTimePeriod(
            projectId: ObjectId,
            daysAgo: Int,
            documentType: RagDocumentType? = null
        ): RagDocumentFilter {
            val cutoffTime = Instant.now().minusSeconds(daysAgo * 24 * 60 * 60L)
            return RagDocumentFilter(
                projectId = projectId,
                documentType = documentType,
                createdAfter = cutoffTime
            )
        }
        
        /**
         * Create a filter specifically for meetings from a time period.
         */
        fun meetingsFromPeriod(projectId: ObjectId, daysAgo: Int): RagDocumentFilter {
            return fromTimePeriod(projectId, daysAgo, RagDocumentType.MEETING)
        }
        
        /**
         * Create a filter for documents of a specific type.
         */
        fun byType(projectId: ObjectId, documentType: RagDocumentType): RagDocumentFilter {
            return RagDocumentFilter(
                projectId = projectId,
                documentType = documentType
            )
        }
        
        /**
         * Create a filter for code documents from a time period.
         */
        fun codeFromPeriod(projectId: ObjectId, daysAgo: Int): RagDocumentFilter {
            return fromTimePeriod(projectId, daysAgo, RagDocumentType.CODE)
        }
        
        /**
         * Create a filter for TODO documents from a time period.
         */
        fun todosFromPeriod(projectId: ObjectId, daysAgo: Int): RagDocumentFilter {
            val cutoffTime = Instant.now().minusSeconds(daysAgo * 24 * 60 * 60L)
            return RagDocumentFilter(
                projectId = projectId,
                createdAfter = cutoffTime
            ).let { baseFilter ->
                // Include all TODO-related document types
                baseFilter.copy(
                    documentType = null // We'll filter for TODO types in application logic
                )
            }
        }
        
        /**
         * Create a filter for git history from a time period.
         */
        fun gitHistoryFromPeriod(projectId: ObjectId, daysAgo: Int): RagDocumentFilter {
            return fromTimePeriod(projectId, daysAgo, RagDocumentType.GIT_HISTORY)
        }
    }
    
    /**
     * Check if this filter matches TODO-related document types.
     * Note: Using UNKNOWN type as placeholder since TODO types are not defined in enum.
     */
    fun matchesTodoTypes(documentType: RagDocumentType): Boolean {
        return documentType == RagDocumentType.UNKNOWN
    }
}