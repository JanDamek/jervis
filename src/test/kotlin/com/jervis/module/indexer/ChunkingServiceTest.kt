package com.jervis.module.indexer

import com.jervis.module.indexer.chunking.Chunk
import com.jervis.module.indexer.chunking.ChunkStrategy
import com.jervis.module.indexer.chunking.ChunkStrategyFactory
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * Test class for ChunkingService
 */
@SpringBootTest
class ChunkingServiceTest {

    /**
     * Test that ChunkingService can be instantiated
     */
    @Test
    fun testChunkingServiceInstantiation() {
        // Create a simple implementation of ChunkStrategy
        val testStrategy = object : ChunkStrategy {
            override fun splitContent(
                content: String, 
                metadata: Map<String, String>, 
                maxChunkSize: Int, 
                overlapSize: Int
            ): List<Chunk> {
                return listOf(Chunk(content, metadata.mapValues { it.value as Any }))
            }

            override fun canHandle(contentType: String): Boolean {
                return true
            }
        }

        // Create a simple implementation of ChunkStrategyFactory
        val testFactory = object : ChunkStrategyFactory(listOf(testStrategy)) {
            // Using the parent implementation
        }

        // Initialize ChunkingService with the test factory and default values
        val chunkingService = ChunkingService(
            chunkStrategyFactory = testFactory,
            textMaxChunkSize = 1024,
            textOverlapSize = 200,
            codeMaxChunkSize = 1024,
            codeOverlapSize = 100
        )

        // Just verify that the service was instantiated
        assert(chunkingService != null)
    }
}
