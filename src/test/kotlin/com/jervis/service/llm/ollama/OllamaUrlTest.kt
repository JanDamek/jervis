package com.jervis.service.llm.ollama

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OllamaUrlTest {

    @Test
    fun `buildApiUrl adds api to bare host`() {
        val base = "http://localhost:11434"
        val full = OllamaUrl.buildApiUrl(base, "/version")
        assertEquals("http://localhost:11434/api/version", full)
    }

    @Test
    fun `buildApiUrl handles trailing slash`() {
        val base = "http://localhost:11434/"
        val full = OllamaUrl.buildApiUrl(base, "version")
        assertEquals("http://localhost:11434/api/version", full)
    }

    @Test
    fun `buildApiUrl does not double append api when base already includes it`() {
        val base = "http://localhost:11434/api"
        val full = OllamaUrl.buildApiUrl(base, "/tags")
        assertEquals("http://localhost:11434/api/tags", full)
    }

    @Test
    fun `buildApiUrl adds http scheme when missing`() {
        val base = "localhost:11434"
        val full = OllamaUrl.buildApiUrl(base, "/version")
        assertEquals("http://localhost:11434/api/version", full)
    }
}
