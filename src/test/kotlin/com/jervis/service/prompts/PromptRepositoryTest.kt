package com.jervis.service.prompts

import com.jervis.configuration.prompts.McpToolType
import com.jervis.configuration.prompts.PromptType
import com.jervis.configuration.prompts.PromptsConfiguration
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class PromptRepositoryTest {
    @Autowired
    private lateinit var promptRepository: PromptRepository

    @Autowired
    private lateinit var promptsConfiguration: PromptsConfiguration

    @Test
    fun `should load prompts configuration from YAML`() {
        println("[DEBUG_LOG] Prompts count: ${promptsConfiguration.prompts.size}")
        println("[DEBUG_LOG] Available prompt types: ${promptsConfiguration.prompts.keys}")

        // Check that configuration is loaded
        assertTrue(promptsConfiguration.prompts.isNotEmpty(), "Prompts should not be empty")
        assertTrue(promptsConfiguration.creativityLevels.isNotEmpty(), "Creativity levels should not be empty")
    }

    @Test
    fun `should retrieve RAG_QUERY description`() {
        println("[DEBUG_LOG] Testing RAG_QUERY description retrieval")

        // Test the specific case that was failing
        val description = promptRepository.getMcpToolDescription(McpToolType.RAG_QUERY)

        println("[DEBUG_LOG] RAG_QUERY description: $description")
        assertNotNull(description, "RAG_QUERY description should not be null")
        assertTrue(description.isNotBlank(), "RAG_QUERY description should not be blank")
        assertTrue(description.contains("semantic search"), "Description should contain 'semantic search'")
    }

    @Test
    fun `should retrieve RAG_QUERY_SYSTEM system prompt`() {
        println("[DEBUG_LOG] Testing RAG_QUERY_SYSTEM system prompt retrieval")

        val systemPrompt = promptRepository.getSystemPrompt(PromptType.RAG_QUERY_SYSTEM)

        println("[DEBUG_LOG] RAG_QUERY_SYSTEM prompt length: ${systemPrompt.length}")
        assertNotNull(systemPrompt, "RAG_QUERY_SYSTEM system prompt should not be null")
        assertTrue(systemPrompt.isNotBlank(), "RAG_QUERY_SYSTEM system prompt should not be blank")
    }
}
