package com.jervis.service.text

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.common.dto.TikaProcessResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TikaTextExtractionServiceTest {

    private val tikaClient: ITikaClient = mock()
    private val service = TikaTextExtractionService(tikaClient)

    @Test
    fun `extractPlainText should return original content if Tika returns empty string`() = runBlocking {
        // Given
        val content = "<html><body><h1>Hello World</h1><p>Some content</p></body></html>"
        val mockResult = TikaProcessResult(
            plainText = "   ", // Simulated 100% loss (only whitespace)
            success = true,
            errorMessage = null
        )
        whenever(tikaClient.process(any())).thenReturn(mockResult)

        // When
        val result = service.extractPlainText(content, "test.html")

        // Then
        assertEquals(content, result)
    }

    @Test
    fun `extractPlainText should return Tika result if not empty`() = runBlocking {
        // Given
        val content = "<html><body><h1>Hello World</h1></body></html>"
        val mockResult = TikaProcessResult(
            plainText = "Hello World",
            success = true,
            errorMessage = null
        )
        whenever(tikaClient.process(any())).thenReturn(mockResult)

        // When
        val result = service.extractPlainText(content)

        // Then
        assertEquals("Hello World", result)
    }
}
