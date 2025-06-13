package com.jervis.module.indexer.chunking

import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Factory for creating and managing chunking strategies.
 * This class selects the appropriate strategy based on content type.
 */
@Component
class ChunkStrategyFactory(
    private val strategies: List<ChunkStrategy>
) {
    private val logger = KotlinLogging.logger {}
    
    /**
     * Get the appropriate chunking strategy for the given content type
     *
     * @param contentType The type of content (e.g., language, format)
     * @return The appropriate chunking strategy
     */
    fun getStrategy(contentType: String): ChunkStrategy {
        val strategy = strategies.find { it.canHandle(contentType) }
        
        if (strategy != null) {
            logger.debug { "Using ${strategy.javaClass.simpleName} for content type: $contentType" }
            return strategy
        }
        
        // Default to the first strategy if none can handle the content type
        logger.warn { "No specific strategy found for content type: $contentType. Using default." }
        return strategies.first()
    }
}