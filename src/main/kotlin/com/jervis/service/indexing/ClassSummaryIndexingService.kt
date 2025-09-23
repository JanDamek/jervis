package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.indexing.dto.ClassSummaryResponse
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.rag.RagIndexingStatusService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString

/**
 * Service for generating and indexing class summaries using LLM analysis.
 * Combines Joern static analysis data with code embeddings for comprehensive class understanding.
 */
@Service
class ClassSummaryIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    private val ragIndexingStatusService: RagIndexingStatusService,
    private val promptRepository: PromptRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of class summary indexing operation
     */
    data class ClassSummaryIndexingResult(
        val processedClasses: Int,
        val skippedClasses: Int,
        val errorClasses: Int,
    )

    /**
     * Class information extracted from Joern and code analysis
     */
    data class ClassInfo(
        val className: String,
        val packageName: String?,
        val filePath: String,
        val methods: List<MethodInfo> = emptyList(),
        val fields: List<FieldInfo> = emptyList(),
        val interfaces: List<String> = emptyList(),
        val superClasses: List<String> = emptyList(),
        val annotations: List<String> = emptyList(),
        val isAbstract: Boolean = false,
        val isInterface: Boolean = false,
        val codeContent: String? = null,
        val joernAnalysis: String? = null,
    )

    /**
     * Method information
     */
    data class MethodInfo(
        val name: String,
        val parameters: List<String> = emptyList(),
        val returnType: String? = null,
        val isPublic: Boolean = true,
        val isStatic: Boolean = false,
        val annotations: List<String> = emptyList(),
    )

    /**
     * Field information
     */
    data class FieldInfo(
        val name: String,
        val type: String? = null,
        val isPublic: Boolean = false,
        val isStatic: Boolean = false,
        val isFinal: Boolean = false,
    )

    /**
     * Index class summaries from Joern analysis and code embeddings
     */
    suspend fun indexClassSummaries(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
        gitCommitHash: String,
    ): ClassSummaryIndexingResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Starting class summary indexing for project: ${project.name}" }

                // Extract class information from Joern analysis
                val classes = extractClassesFromJoern(joernDir)
                logger.info { "Found ${classes.size} classes to analyze for project: ${project.name}" }

                // Enhance class information with code content
                val enhancedClasses = enhanceClassesWithCode(classes, projectPath)
                logger.info { "Enhanced ${enhancedClasses.size} classes with code content" }

                var processedClasses = 0
                var errorClasses = 0

                for (classInfo in enhancedClasses) {
                    try {
                        val success = generateAndIndexClassSummary(project, classInfo, gitCommitHash)
                        if (success) {
                            processedClasses++
                        } else {
                            errorClasses++
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to index class summary: ${classInfo.className}" }
                        errorClasses++
                    }
                }

                val result = ClassSummaryIndexingResult(processedClasses, 0, errorClasses)
                logger.info {
                    "Class summary indexing completed for project: ${project.name} - " +
                        "Processed: $processedClasses, Errors: $errorClasses"
                }

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during class summary indexing for project: ${project.name}" }
                ClassSummaryIndexingResult(0, 0, 1)
            }
        }

    /**
     * Generate LLM-based class summary and index it
     */
    private suspend fun generateAndIndexClassSummary(
        project: ProjectDocument,
        classInfo: ClassInfo,
        gitCommitHash: String,
    ): Boolean {
        try {
            logger.debug { "Generating class summary for: ${classInfo.className}" }

            val llmResponse =
                llmGateway.callLlm(
                    type = PromptTypeEnum.CLASS_SUMMARY,
                    userPrompt = "",
                    quick = false,
                    responseSchema = ClassSummaryResponse(),
                    mappingValue = mapOf(
                        "className" to classInfo.className,
                        "filePath" to classInfo.filePath,
                        "code" to (classInfo.fields.joinToString("\n") + "\n\n" + classInfo.methods.joinToString("\n") { "${it.name}(${it.parameters.joinToString(", ")})" }),
                        "relations" to buildString {
                            if (classInfo.superClasses.isNotEmpty()) {
                                append("Extends: ${classInfo.superClasses.joinToString(", ")}. ")
                            }
                            if (classInfo.interfaces.isNotEmpty()) {
                                append("Implements: ${classInfo.interfaces.joinToString(", ")}. ")
                            }
                            if (classInfo.annotations.isNotEmpty()) {
                                append("Annotations: ${classInfo.annotations.joinToString(", ")}")
                            }
                        }
                    )
                )

            val classSummary =
                buildString {
                    appendLine("Class Summary: ${classInfo.className}")
                    appendLine("=".repeat(60))
                    appendLine("Package: ${classInfo.packageName ?: "unknown"}")
                    appendLine("File: ${classInfo.filePath}")
                    appendLine()
                    appendLine("Analysis:")
                    appendLine(llmResponse.summary)
                    appendLine()
                    appendLine("Technical Details:")
                    appendLine("- Methods: ${classInfo.methods.size}")
                    appendLine("- Fields: ${classInfo.fields.size}")
                    if (classInfo.interfaces.isNotEmpty()) {
                        appendLine("- Implements: ${classInfo.interfaces.joinToString(", ")}")
                    }
                    if (classInfo.superClasses.isNotEmpty()) {
                        appendLine("- Extends: ${classInfo.superClasses.joinToString(", ")}")
                    }
                    if (classInfo.annotations.isNotEmpty()) {
                        appendLine("- Annotations: ${classInfo.annotations.joinToString(", ")}")
                    }
                    appendLine()
                    appendLine("---")
                    appendLine("Generated by: LLM Analysis")
                    appendLine("Based on: Joern static analysis + code embeddings")
                    appendLine("Project: ${project.name}")
                }

            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, classSummary)

            val ragDocument =
                RagDocument(
                    projectId = project.id,
                    documentType = RagDocumentType.CLASS_SUMMARY,
                    ragSourceType = RagSourceType.CLASS,
                    pageContent = classSummary,
                    clientId = project.clientId,
                    source = "class://${project.name}/${classInfo.className}",
                    path = classInfo.filePath,
                    packageName = classInfo.packageName,
                    className = classInfo.className,
                    module = classInfo.className,
                    language = inferLanguageFromPath(classInfo.filePath),
                    gitCommitHash = gitCommitHash,
                )

            // Track indexing status for this class summary
            try {
                ragIndexingStatusService.startIndexing(
                    projectId = project.id,
                    filePath = classInfo.filePath,
                    gitCommitHash = gitCommitHash,
                    ragSourceType = RagSourceType.CLASS,
                    fileContent = classSummary.toByteArray(),
                    language = inferLanguageFromPath(classInfo.filePath),
                    module = classInfo.className,
                )
            } catch (e: Exception) {
                logger.warn(e) { "Error tracking indexing status for class: ${classInfo.className}" }
            }

            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
            logger.debug { "Successfully indexed class summary for: ${classInfo.className}" }

            // Index each method separately as required by the issue
            indexMethodsSeparately(project, classInfo, gitCommitHash)

            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate class summary for: ${classInfo.className}" }
            return false
        }
    }

    /**
     * Index each method separately with class context
     * This meets the requirement: "zvláš s popisem každé methody ve tříde" (separately with each method description in the class)
     */
    private suspend fun indexMethodsSeparately(
        project: ProjectDocument,
        classInfo: ClassInfo,
        gitCommitHash: String,
    ) {
        if (classInfo.methods.isEmpty()) {
            logger.debug { "No methods to index for class: ${classInfo.className}" }
            return
        }

        for (method in classInfo.methods) {
            try {
                val methodDescription = buildMethodDescription(method, classInfo, project)
                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, methodDescription)

                val ragDocument = RagDocument(
                    projectId = project.id,
                    documentType = RagDocumentType.METHOD_DESCRIPTION,
                    ragSourceType = RagSourceType.METHOD,
                    pageContent = methodDescription,
                    clientId = project.clientId,
                    source = "method://${project.name}/${classInfo.className}#${method.name}",
                    path = classInfo.filePath,
                    packageName = classInfo.packageName,
                    className = classInfo.className,
                    methodName = method.name,
                    module = "${classInfo.className}#${method.name}",
                    language = inferLanguageFromPath(classInfo.filePath),
                    gitCommitHash = gitCommitHash,
                )

                // Track indexing status for this method
                try {
                    ragIndexingStatusService.startIndexing(
                        projectId = project.id,
                        filePath = "${classInfo.filePath}#${method.name}",
                        gitCommitHash = gitCommitHash,
                        ragSourceType = RagSourceType.METHOD,
                        fileContent = methodDescription.toByteArray(),
                        language = inferLanguageFromPath(classInfo.filePath),
                        module = "${classInfo.className}#${method.name}",
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Error tracking indexing status for method: ${classInfo.className}#${method.name}" }
                }

                vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
                logger.debug { "Successfully indexed method: ${classInfo.className}#${method.name}" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to index method: ${classInfo.className}#${method.name}" }
            }
        }

        logger.debug { "Completed indexing of ${classInfo.methods.size} methods for class: ${classInfo.className}" }
    }

    /**
     * Build method description for embedding
     */
    private fun buildMethodDescription(method: MethodInfo, classInfo: ClassInfo, project: ProjectDocument): String {
        return buildString {
            appendLine("Method: ${method.name}")
            appendLine("=".repeat(60))
            appendLine("Class: ${classInfo.className}")
            appendLine("Package: ${classInfo.packageName ?: "unknown"}")
            appendLine("File: ${classInfo.filePath}")
            appendLine()

            // Method signature details
            appendLine("Method Signature:")
            val modifiers = buildList {
                if (method.isPublic) add("public")
                if (method.isStatic) add("static")
            }.joinToString(" ")

            val params = if (method.parameters.isNotEmpty()) {
                method.parameters.joinToString(", ")
            } else {
                "()"
            }

            appendLine("$modifiers ${method.returnType ?: "void"} ${method.name}($params)")

            if (method.annotations.isNotEmpty()) {
                appendLine("Annotations: ${method.annotations.joinToString(", ")}")
            }

            appendLine()
            appendLine("Class Context:")
            appendLine("- Class Type: ${
                if (classInfo.isInterface) "Interface" 
                else if (classInfo.isAbstract) "Abstract Class" 
                else "Class"
            }")
            if (classInfo.interfaces.isNotEmpty()) {
                appendLine("- Class Implements: ${classInfo.interfaces.joinToString(", ")}")
            }
            if (classInfo.superClasses.isNotEmpty()) {
                appendLine("- Class Extends: ${classInfo.superClasses.joinToString(", ")}")
            }
            appendLine("- Total Methods in Class: ${classInfo.methods.size}")
            appendLine("- Total Fields in Class: ${classInfo.fields.size}")

            // Include Joern analysis context if available
            classInfo.joernAnalysis?.let { analysis ->
                appendLine()
                appendLine("Joern Analysis Context:")
                appendLine(analysis)
            }

            // Include relevant code context if available
            classInfo.codeContent?.let { code ->
                appendLine()
                appendLine("Code Context (Class Extract):")
                val codePreview = if (code.length > 1000) {
                    code.take(1000) + "\n... (code truncated)"
                } else {
                    code
                }
                appendLine("```")
                appendLine(codePreview)
                appendLine("```")
            }

            appendLine()
            appendLine("---")
            appendLine("Generated by: Joern Static Analysis + Method Indexing (Batch Optimized)")
            appendLine("Indexed as: Individual Method Entry")
            appendLine("Project: ${project.name}")
            appendLine("Searchable by: method name, class context, Joern analysis results")
        }
    }


    /**
     * Build detailed analysis prompt for LLM using configured CLASS_SUMMARY prompt
     */
    private fun buildClassAnalysisPrompt(classInfo: ClassInfo): String =
        buildString {
            appendLine("=== CLASS INFORMATION ===")
            appendLine()
            appendLine("Class Name: ${classInfo.className}")
            appendLine("Package: ${classInfo.packageName ?: "unknown"}")
            appendLine("File Path: ${classInfo.filePath}")

            if (classInfo.isInterface) {
                appendLine("Type: Interface")
            } else if (classInfo.isAbstract) {
                appendLine("Type: Abstract Class")
            } else {
                appendLine("Type: Class")
            }

            if (classInfo.superClasses.isNotEmpty()) {
                appendLine("Extends: ${classInfo.superClasses.joinToString(", ")}")
            }

            if (classInfo.interfaces.isNotEmpty()) {
                appendLine("Implements: ${classInfo.interfaces.joinToString(", ")}")
            }

            if (classInfo.annotations.isNotEmpty()) {
                appendLine("Annotations: ${classInfo.annotations.joinToString(", ")}")
            }

            if (classInfo.methods.isNotEmpty()) {
                appendLine()
                appendLine("Methods (${classInfo.methods.size}):")
                classInfo.methods.take(20).forEach { method ->
                    val modifiers =
                        buildList {
                            if (method.isPublic) add("public")
                            if (method.isStatic) add("static")
                        }.joinToString(" ")

                    val params =
                        if (method.parameters.isNotEmpty()) {
                            method.parameters.joinToString(", ")
                        } else {
                            "()"
                        }

                    appendLine("- $modifiers ${method.returnType ?: "void"} ${method.name}($params)")

                    if (method.annotations.isNotEmpty()) {
                        appendLine("    Annotations: ${method.annotations.joinToString(", ")}")
                    }
                }
                if (classInfo.methods.size > 20) {
                    appendLine("... and ${classInfo.methods.size - 20} more methods")
                }
            }

            if (classInfo.fields.isNotEmpty()) {
                appendLine()
                appendLine("Fields (${classInfo.fields.size}):")
                classInfo.fields.take(15).forEach { field ->
                    val modifiers =
                        buildList {
                            if (field.isPublic) add("public")
                            if (field.isStatic) add("static")
                            if (field.isFinal) add("final")
                        }.joinToString(" ")

                    appendLine("- $modifiers ${field.type ?: "unknown"} ${field.name}")
                }
                if (classInfo.fields.size > 15) {
                    appendLine("... and ${classInfo.fields.size - 15} more fields")
                }
            }

            classInfo.joernAnalysis?.let { analysis ->
                appendLine()
                appendLine("Joern Static Analysis:")
                appendLine(analysis)
            }

            classInfo.codeContent?.let { code ->
                // Include a portion of the actual code for context
                val codePreview =
                    if (code.length > 2000) {
                        code.take(2000) + "\n... (code truncated)"
                    } else {
                        code
                    }
                appendLine()
                appendLine("Code Sample:")
                appendLine("```")
                appendLine(codePreview)
                appendLine("```")
            }
        }

    /**
     * Extract class information from Joern analysis files
     */
    private suspend fun extractClassesFromJoern(joernDir: Path): List<ClassInfo> =
        withContext(Dispatchers.IO) {
            try {
                val classes = mutableListOf<ClassInfo>()

                // Find class-related files in Joern output
                Files
                    .walk(joernDir)
                    .filter { it.isRegularFile() }
                    .filter { path ->
                        val fileName = path.fileName.toString().lowercase()
                        fileName.endsWith(".json") && (
                            fileName.contains("class") ||
                                fileName.contains("method") ||
                                fileName.contains("type") ||
                                fileName.contains("cpg")
                        )
                    }.forEach { file ->
                        try {
                            val content = Files.readString(file)
                            val descriptions = extractClassDescriptionsFromText(content, file.pathString)
                            // Create basic class info from text descriptions for RAG indexing
                            descriptions.forEach { description ->
                                val classInfo = ClassInfo(
                                    className = extractNameFromDescription(description),
                                    packageName = null,
                                    filePath = file.pathString,
                                    methods = emptyList(),
                                    fields = emptyList(),
                                    interfaces = emptyList(),
                                    superClasses = emptyList(),
                                    annotations = emptyList(),
                                    isAbstract = false,
                                    isInterface = false,
                                    joernAnalysis = description
                                )
                                classes.add(classInfo)
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to extract class descriptions from file: ${file.fileName}" }
                        }
                    }

                // Remove duplicates based on class name and package
                classes.distinctBy { "${it.packageName}.${it.className}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to extract classes from Joern directory" }
                emptyList()
            }
        }

    /**
     * Extract textual class descriptions from Joern output for RAG indexing
     */
    private fun extractClassDescriptionsFromText(
        content: String,
        fileName: String,
    ): List<String> {
        return try {
            val descriptions = mutableListOf<String>()
            val lines = content.lines()
            
            // Extract meaningful textual information from Joern output
            for (line in lines) {
                val trimmedLine = line.trim()
                
                // Skip empty lines and pure log lines
                if (trimmedLine.isEmpty() || 
                    trimmedLine.startsWith("[") || 
                    trimmedLine.startsWith("WARNING:") ||
                    trimmedLine.startsWith("executing") ||
                    trimmedLine.startsWith("--")) {
                    continue
                }
                
                // Look for class-related information
                if (trimmedLine.contains("class", ignoreCase = true) ||
                    trimmedLine.contains("method", ignoreCase = true) ||
                    trimmedLine.contains("function", ignoreCase = true) ||
                    trimmedLine.contains("interface", ignoreCase = true) ||
                    trimmedLine.contains("type", ignoreCase = true)) {
                    descriptions.add(trimmedLine)
                }
            }
            
            descriptions
        } catch (e: Exception) {
            logger.debug(e) { "Failed to extract class descriptions from file: $fileName" }
            emptyList()
        }
    }

    /**
     * Extract a simple name from description for class identification
     */
    private fun extractNameFromDescription(description: String): String {
        // Try to extract a meaningful name from the description
        val words = description.split("\\s+".toRegex())
        
        // Look for class-like names (containing dots or CamelCase)
        for (word in words) {
            if (word.contains(".") && !word.startsWith(".") && !word.endsWith(".")) {
                return word.substringAfterLast(".")
            }
            if (word.matches("[A-Z][a-zA-Z0-9]*".toRegex()) && word.length > 2) {
                return word
            }
        }
        
        // Fallback to first meaningful word
        return words.firstOrNull { it.length > 2 && it.matches("[a-zA-Z].*".toRegex()) } 
            ?: "unknown-class"
    }

    /**
     * Enhance classes with actual code content from files
     */
    private suspend fun enhanceClassesWithCode(
        classes: List<ClassInfo>,
        projectPath: Path,
    ): List<ClassInfo> =
        withContext(Dispatchers.IO) {
            classes.map { classInfo ->
                try {
                    val fullPath = projectPath.resolve(classInfo.filePath)
                    if (Files.exists(fullPath)) {
                        val codeContent = Files.readString(fullPath)
                        classInfo.copy(codeContent = codeContent)
                    } else {
                        classInfo
                    }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to read code for class: ${classInfo.className}" }
                    classInfo
                }
            }
        }

    /**
     * Infer programming language from file path
     */
    private fun inferLanguageFromPath(filePath: String): String =
        when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "scala" -> "scala"
            "groovy" -> "groovy"
            "js", "ts" -> "javascript"
            "py" -> "python"
            "cs" -> "csharp"
            "cpp", "cc", "cxx" -> "cpp"
            "go" -> "go"
            "rs" -> "rust"
            else -> "unknown"
        }
}
