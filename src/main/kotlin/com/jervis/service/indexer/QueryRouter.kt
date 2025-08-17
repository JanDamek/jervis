package com.jervis.service.indexer

import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class QueryRouter {
    private val logger = KotlinLogging.logger {}
    
    /**
     * Intelligent routing of queries for embedding optimization
     */
    fun determineEmbeddingStrategy(query: String): EmbeddingStrategy {
        return when {
            // Symbolic queries - only code embedding
            query.contains(Regex("[A-Z]\\w+\\.\\w+")) -> {
                logger.debug { "Detected symbolic query: $query" }
                EmbeddingStrategy.CODE_ONLY
            }
            query.contains(Regex("`[^`]+`")) -> {
                logger.debug { "Detected code snippet query: $query" }
                EmbeddingStrategy.CODE_ONLY
            }
            query.contains(Regex("\\([^)]*\\)")) && containsCodeKeywords(query) -> {
                logger.debug { "Detected function call query: $query" }
                EmbeddingStrategy.CODE_ONLY
            }
            
            // Meeting/communication queries - only text embedding
            query.contains("meeting", ignoreCase = true) -> {
                logger.debug { "Detected meeting query: $query" }
                EmbeddingStrategy.TEXT_ONLY
            }
            query.contains("email", ignoreCase = true) -> {
                logger.debug { "Detected email query: $query" }
                EmbeddingStrategy.TEXT_ONLY
            }
            query.contains("discussion", ignoreCase = true) -> {
                logger.debug { "Detected discussion query: $query" }
                EmbeddingStrategy.TEXT_ONLY
            }
            query.contains("communication", ignoreCase = true) -> {
                logger.debug { "Detected communication query: $query" }
                EmbeddingStrategy.TEXT_ONLY
            }
            
            // Documentation queries - primarily text with some code
            containsDocumentationKeywords(query) -> {
                logger.debug { "Detected documentation query: $query" }
                EmbeddingStrategy.TEXT_PRIORITY
            }
            
            // API/SDK queries - balanced approach
            containsApiKeywords(query) -> {
                logger.debug { "Detected API query: $query" }
                EmbeddingStrategy.BALANCED
            }
            
            // Implementation queries - code priority
            containsImplementationKeywords(query) -> {
                logger.debug { "Detected implementation query: $query" }
                EmbeddingStrategy.CODE_PRIORITY
            }
            
            // Architecture queries - balanced approach
            containsArchitectureKeywords(query) -> {
                logger.debug { "Detected architecture query: $query" }
                EmbeddingStrategy.BALANCED
            }
            
            // General queries - both types
            else -> {
                logger.debug { "Using default strategy for query: $query" }
                EmbeddingStrategy.BOTH
            }
        }
    }
    
    /**
     * Determine search collection weights based on strategy
     */
    fun getCollectionWeights(strategy: EmbeddingStrategy): CollectionWeights {
        return when (strategy) {
            EmbeddingStrategy.CODE_ONLY -> CollectionWeights(
                semanticCode = 1.0f,
                semanticText = 0.0f
            )
            EmbeddingStrategy.TEXT_ONLY -> CollectionWeights(
                semanticCode = 0.0f,
                semanticText = 1.0f
            )
            EmbeddingStrategy.CODE_PRIORITY -> CollectionWeights(
                semanticCode = 0.7f,
                semanticText = 0.3f
            )
            EmbeddingStrategy.TEXT_PRIORITY -> CollectionWeights(
                semanticCode = 0.3f,
                semanticText = 0.7f
            )
            EmbeddingStrategy.BALANCED -> CollectionWeights(
                semanticCode = 0.5f,
                semanticText = 0.5f
            )
            EmbeddingStrategy.BOTH -> CollectionWeights(
                semanticCode = 0.5f,
                semanticText = 0.5f
            )
        }
    }
    
    /**
     * Determine result limits per collection
     */
    fun getSearchLimits(strategy: EmbeddingStrategy, totalLimit: Int): SearchLimits {
        val weights = getCollectionWeights(strategy)
        return SearchLimits(
            semanticCodeLimit = (totalLimit * weights.semanticCode).toInt().coerceAtLeast(1),
            semanticTextLimit = (totalLimit * weights.semanticText).toInt().coerceAtLeast(1)
        )
    }
    
    /**
     * Analyze query complexity for optimization hints
     */
    fun analyzeQueryComplexity(query: String): QueryComplexity {
        val wordCount = query.split(Regex("\\s+")).size
        val hasSpecialCharacters = query.contains(Regex("[{}\\[\\]()\"'`]"))
        val hasCodePatterns = containsCodeKeywords(query)
        val hasMultipleConcepts = query.split(Regex("\\s+and\\s+|\\s+or\\s+", RegexOption.IGNORE_CASE)).size > 1
        
        return QueryComplexity(
            wordCount = wordCount,
            isComplex = wordCount > 10 || hasMultipleConcepts,
            hasCodePatterns = hasCodePatterns,
            hasSpecialCharacters = hasSpecialCharacters,
            suggestedChunkExpansion = if (hasMultipleConcepts) 2 else 1
        )
    }
    
    private fun containsCodeKeywords(query: String): Boolean {
        val codeKeywords = listOf(
            "function", "method", "class", "interface", "variable", "parameter",
            "return", "throw", "catch", "try", "if", "else", "for", "while",
            "import", "package", "public", "private", "protected", "static",
            "final", "abstract", "override", "suspend", "async", "await"
        )
        return codeKeywords.any { query.contains(it, ignoreCase = true) }
    }
    
    private fun containsDocumentationKeywords(query: String): Boolean {
        val docKeywords = listOf(
            "documentation", "readme", "guide", "tutorial", "example",
            "how to", "explain", "describe", "overview", "introduction",
            "getting started", "quick start", "user manual"
        )
        return docKeywords.any { query.contains(it, ignoreCase = true) }
    }
    
    private fun containsApiKeywords(query: String): Boolean {
        val apiKeywords = listOf(
            "api", "endpoint", "request", "response", "http", "rest",
            "graphql", "json", "xml", "service", "client", "sdk"
        )
        return apiKeywords.any { query.contains(it, ignoreCase = true) }
    }
    
    private fun containsImplementationKeywords(query: String): Boolean {
        val implementationKeywords = listOf(
            "implement", "code", "algorithm", "logic", "solution",
            "fix", "bug", "issue", "problem", "error", "debug"
        )
        return implementationKeywords.any { query.contains(it, ignoreCase = true) }
    }
    
    private fun containsArchitectureKeywords(query: String): Boolean {
        val architectureKeywords = listOf(
            "architecture", "design", "pattern", "structure", "component",
            "module", "system", "framework", "library", "dependency"
        )
        return architectureKeywords.any { query.contains(it, ignoreCase = true) }
    }
}

/**
 * Enum representing different embedding strategies
 */
enum class EmbeddingStrategy {
    TEXT_ONLY,      // Only use text embeddings
    CODE_ONLY,      // Only use code embeddings
    TEXT_PRIORITY,  // Prioritize text embeddings (70/30)
    CODE_PRIORITY,  // Prioritize code embeddings (70/30)
    BALANCED,       // Equal weight (50/50)
    BOTH            // Use both with equal weight (same as BALANCED)
}

/**
 * Data class representing collection weights
 */
data class CollectionWeights(
    val semanticCode: Float,
    val semanticText: Float
)

/**
 * Data class representing search limits per collection
 */
data class SearchLimits(
    val semanticCodeLimit: Int,
    val semanticTextLimit: Int
)

/**
 * Data class representing query complexity analysis
 */
data class QueryComplexity(
    val wordCount: Int,
    val isComplex: Boolean,
    val hasCodePatterns: Boolean,
    val hasSpecialCharacters: Boolean,
    val suggestedChunkExpansion: Int
)