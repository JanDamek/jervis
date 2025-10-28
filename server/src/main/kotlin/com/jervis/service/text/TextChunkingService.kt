package com.jervis.service.text

import com.jervis.configuration.TextChunkingProperties
import dev.langchain4j.data.document.Document
import dev.langchain4j.data.document.DocumentSplitter
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.data.segment.TextSegment
import org.springframework.stereotype.Service

@Service
class TextChunkingService(
    textChunkingProperties: TextChunkingProperties,
) {
    private val maxChars: Int =
        (textChunkingProperties.maxTokens * textChunkingProperties.charsPerToken).toInt()

    private val overlapChars: Int =
        (maxChars * textChunkingProperties.overlapPercentage) / 100

    private val splitter: DocumentSplitter =
        DocumentSplitters.recursive(maxChars, overlapChars)

    fun splitText(text: String): List<TextSegment> =
        text
            .takeIf { it.isNotBlank() }
            ?.let { splitDocument(it) }
            ?: emptyList()

    private fun splitDocument(text: String): List<TextSegment> = Document.from(text).let { splitter.split(it) }
}
