package com.jervis.service.text

import com.jervis.configuration.TextChunkingProperties
import dev.langchain4j.data.document.Document
import dev.langchain4j.data.document.DocumentSplitter
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.data.segment.TextSegment
import org.springframework.stereotype.Service

@Service
class TextChunkingService(
    private val textChunkingProperties: TextChunkingProperties,
) {
    fun splitText(text: String): List<TextSegment> {
        if (text.isBlank()) return emptyList()

        val maxTokens = textChunkingProperties.maxTokens
        val overlapTokens = (maxTokens * textChunkingProperties.overlapPercentage) / 100

        val splitter: DocumentSplitter = DocumentSplitters.recursive(maxTokens, overlapTokens)
        val document = Document.from(text)
        return splitter.split(document)
    }
}
