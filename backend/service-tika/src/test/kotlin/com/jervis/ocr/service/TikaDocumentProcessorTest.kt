package com.jervis.ocr.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class TikaDocumentProcessorTest {

    private val processor = TikaDocumentProcessor(120000L)

    @Test
    fun `processDocumentStream should handle empty input stream`() {
        val inputStream = ByteArrayInputStream(byteArrayOf())
        val result = processor.processDocumentStream(inputStream, "empty.txt")
        
        // When Tika parses an empty stream, it might fail or return empty result depending on its version and config.
        // We just care that it doesn't crash the service.
        assertEquals("", result.plainText)
    }

    @Test
    fun `processDocumentStream should extract text from simple string`() {
        val content = "Hello Tika World"
        val inputStream = ByteArrayInputStream(content.toByteArray())
        val result = processor.processDocumentStream(inputStream, "hello.txt")
        
        assertTrue(result.success)
        assertEquals(content, result.plainText.trim())
    }

    @Test
    fun `processDocumentStream should handle HTML content`() {
        val content = "<html><body><h1>Title</h1><p>Paragraph</p></body></html>"
        val inputStream = ByteArrayInputStream(content.toByteArray())
        val result = processor.processDocumentStream(inputStream, "test.html")
        
        assertTrue(result.success)
        // BodyContentHandler usually extracts text with some whitespace/newlines
        assertTrue(result.plainText.contains("Title"))
        assertTrue(result.plainText.contains("Paragraph"))
        // It shouldn't be empty if input is non-empty HTML
        assertTrue(result.plainText.isNotBlank())
    }
}
