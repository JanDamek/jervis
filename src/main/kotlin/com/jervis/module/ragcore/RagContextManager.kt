package com.jervis.module.ragcore

import com.jervis.rag.Document
import org.springframework.stereotype.Service

/**
 * Service for managing context in the RAG process.
 * This service builds context from retrieved documents for use in LLM prompts.
 */
@Service
class RagContextManager {
    
    /**
     * Build context from retrieved documents
     * 
     * @param query The user query
     * @param documents The retrieved documents
     * @param options Additional options for context building
     * @return The built context as a string
     */
    fun buildContext(query: String, documents: List<Document>, options: Map<String, Any> = emptyMap()): String {
        val maxTokens = options["max_tokens"] as? Int ?: 4000
        val contextBuilder = StringBuilder()
        
        // Add a header to the context
        contextBuilder.append("Context information for query: $query\n\n")
        
        // Add each document to the context
        documents.forEachIndexed { index, doc ->
            val source = doc.metadata["file_path"] as? String ?: "Document $index"
            contextBuilder.append("Source: $source\n")
            contextBuilder.append("Content: ${doc.pageContent}\n\n")
        }
        
        // In a real implementation, we would truncate the context to fit within maxTokens
        // For now, we'll just return the built context
        return contextBuilder.toString()
    }
    
    /**
     * Rerank documents based on relevance to the query
     * 
     * @param query The user query
     * @param documents The retrieved documents
     * @return The reranked documents
     */
    fun rerankDocuments(query: String, documents: List<Document>): List<Document> {
        // In a real implementation, this would use a reranking model
        // For now, we'll just return the documents in the original order
        return documents
    }
    
    /**
     * Merge similar documents to reduce redundancy
     * 
     * @param documents The documents to merge
     * @return The merged documents
     */
    fun mergeDocuments(documents: List<Document>): List<Document> {
        // In a real implementation, this would merge similar documents
        // For now, we'll just return the original documents
        return documents
    }
}