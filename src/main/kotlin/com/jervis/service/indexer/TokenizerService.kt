package com.jervis.service.indexer

import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class TokenizerService {
    private val logger = KotlinLogging.logger {}
    
    // Approximation of tokenizer for quick estimates
    private val avgCharsPerToken = 3.5
    
    /**
     * Approximate token count in text
     */
    fun estimateTokenCount(text: String): Int {
        // Simple heuristic - for more precise results can use tiktoken
        val words = text.split(Regex("\\s+")).size
        val chars = text.length
        return maxOf(words, (chars / avgCharsPerToken).toInt())
    }
    
    /**
     * Split text into chunks by token limit
     */
    fun chunkTextByTokens(text: String, targetTokens: Int, maxTokens: Int = 400): List<String> {
        if (estimateTokenCount(text) <= maxTokens) {
            return listOf(text)
        }
        
        val sentences = text.split(Regex("[.!?]\\s+"))
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()
        var currentTokens = 0
        
        sentences.forEach { sentence ->
            val sentenceTokens = estimateTokenCount(sentence)
            
            if (currentTokens + sentenceTokens > maxTokens && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
                currentTokens = 0
            }
            
            currentChunk.append(sentence).append(". ")
            currentTokens += sentenceTokens
        }
        
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }
        
        return chunks.ifEmpty { listOf(text) }
    }
    
    /**
     * Trim code to target token count
     */
    fun trimCodeToTokens(code: String, targetTokens: Int): String {
        if (estimateTokenCount(code) <= targetTokens) {
            return code
        }
        
        val lines = code.split('\n')
        val result = StringBuilder()
        var tokens = 0
        
        for (line in lines) {
            val lineTokens = estimateTokenCount(line)
            if (tokens + lineTokens > targetTokens) {
                break
            }
            result.appendLine(line)
            tokens += lineTokens
        }
        
        return result.toString().trim()
    }
    
    /**
     * Smart chunking for code with context preservation
     */
    fun chunkCodeByStructure(code: String, targetTokens: Int = 350, maxTokens: Int = 400): List<CodeChunk> {
        if (estimateTokenCount(code) <= maxTokens) {
            return listOf(CodeChunk(
                content = code,
                type = "full",
                startLine = 1,
                endLine = code.split('\n').size,
                tokenCount = estimateTokenCount(code)
            ))
        }
        
        val lines = code.split('\n')
        val chunks = mutableListOf<CodeChunk>()
        var currentChunk = StringBuilder()
        var currentTokens = 0
        var startLine = 1
        var currentLine = 1
        
        for (line in lines) {
            val lineTokens = estimateTokenCount(line)
            
            // Check if adding this line would exceed the limit
            if (currentTokens + lineTokens > maxTokens && currentChunk.isNotEmpty()) {
                // Save current chunk
                chunks.add(CodeChunk(
                    content = currentChunk.toString().trim(),
                    type = determineChunkType(currentChunk.toString()),
                    startLine = startLine,
                    endLine = currentLine - 1,
                    tokenCount = currentTokens
                ))
                
                // Start new chunk
                currentChunk = StringBuilder()
                currentTokens = 0
                startLine = currentLine
            }
            
            currentChunk.appendLine(line)
            currentTokens += lineTokens
            currentLine++
        }
        
        // Add final chunk
        if (currentChunk.isNotEmpty()) {
            chunks.add(CodeChunk(
                content = currentChunk.toString().trim(),
                type = determineChunkType(currentChunk.toString()),
                startLine = startLine,
                endLine = currentLine - 1,
                tokenCount = currentTokens
            ))
        }
        
        return chunks
    }
    
    /**
     * Determine chunk type based on content
     */
    private fun determineChunkType(content: String): String {
        val trimmed = content.trim()
        return when {
            trimmed.contains(Regex("class\\s+\\w+")) -> "class"
            trimmed.contains(Regex("interface\\s+\\w+")) -> "interface"
            trimmed.contains(Regex("fun\\s+\\w+")) -> "function"
            trimmed.contains(Regex("def\\s+\\w+")) -> "function"
            trimmed.contains(Regex("function\\s+\\w+")) -> "function"
            trimmed.startsWith("//") || trimmed.startsWith("/*") -> "comment"
            trimmed.startsWith("import") || trimmed.startsWith("package") -> "import"
            else -> "code"
        }
    }
    
    /**
     * Extract function/method signatures for indexing
     */
    fun extractSignatures(code: String): List<FunctionSignature> {
        val signatures = mutableListOf<FunctionSignature>()
        val lines = code.split('\n')
        
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            // Kotlin function pattern
            val kotlinFunctionPattern = Regex("(public|private|protected|internal)?\\s*fun\\s+(\\w+)\\s*\\(([^)]*)\\)")
            kotlinFunctionPattern.find(trimmed)?.let { match ->
                signatures.add(FunctionSignature(
                    name = match.groupValues[2],
                    parameters = match.groupValues[3],
                    visibility = match.groupValues[1].takeIf { it.isNotEmpty() } ?: "public",
                    lineNumber = index + 1,
                    language = "kotlin"
                ))
            }
            
            // Java method pattern
            val javaMethodPattern = Regex("(public|private|protected)?\\s*(static)?\\s*\\w+\\s+(\\w+)\\s*\\(([^)]*)\\)")
            javaMethodPattern.find(trimmed)?.let { match ->
                signatures.add(FunctionSignature(
                    name = match.groupValues[3],
                    parameters = match.groupValues[4],
                    visibility = match.groupValues[1].takeIf { it.isNotEmpty() } ?: "public",
                    lineNumber = index + 1,
                    language = "java"
                ))
            }
        }
        
        return signatures
    }
}

/**
 * Data class representing a code chunk with metadata
 */
data class CodeChunk(
    val content: String,
    val type: String,
    val startLine: Int,
    val endLine: Int,
    val tokenCount: Int
)

/**
 * Data class representing a function signature
 */
data class FunctionSignature(
    val name: String,
    val parameters: String,
    val visibility: String,
    val lineNumber: Int,
    val language: String
)