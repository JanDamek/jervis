package com.jervis.module.indexer

import com.jervis.entity.Project
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

/**
 * Service responsible for extracting TODOs and notes from the codebase.
 * Identifies comments with TODO, FIXME, and other markers.
 */
@Service
class TodoExtractor {
    private val logger = KotlinLogging.logger {}
    
    // Regex patterns for different types of TODOs
    private val singleLineTodoPattern = Pattern.compile("//\\s*(TODO|FIXME|XXX|HACK|BUG|NOTE)\\s*:?\\s*(.*)")
    private val multiLineTodoPattern = Pattern.compile("/\\*\\s*(TODO|FIXME|XXX|HACK|BUG|NOTE)\\s*:?\\s*([\\s\\S]*?)\\*/")
    private val javadocTodoPattern = Pattern.compile("\\*\\s*(TODO|FIXME|XXX|HACK|BUG|NOTE)\\s*:?\\s*(.*)")
    private val kotlinDocTodoPattern = Pattern.compile("\\*\\s*(TODO|FIXME|XXX|HACK|BUG|NOTE)\\s*:?\\s*(.*)")
    private val deprecatedPattern = Pattern.compile("@deprecated\\s+(.*)")
    
    /**
     * Extracts TODOs and notes from a project and returns a list of todos.
     * 
     * @param project The project to analyze
     * @return List of TODOs found in the project
     */
    fun extractTodos(project: Project): List<Todo> {
        logger.info { "Extracting TODOs from project: ${project.name}" }
        val todos = mutableListOf<Todo>()
        
        try {
            val projectPath = Paths.get(project.path)
            
            // Walk through all files in the project
            Files.walk(projectPath)
                .filter { Files.isRegularFile(it) }
                .filter { isRelevantFile(it) }
                .forEach { filePath ->
                    try {
                        val relativePath = projectPath.relativize(filePath).toString()
                        val fileContent = String(Files.readAllBytes(filePath), Charsets.UTF_8)
                        
                        // Extract TODOs from the file
                        extractTodosFromFile(fileContent, relativePath).forEach { todo ->
                            todos.add(todo)
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Error extracting TODOs from file: ${filePath.fileName}" }
                    }
                }
            
            logger.info { "Found ${todos.size} TODOs in project: ${project.name}" }
        } catch (e: Exception) {
            logger.error(e) { "Error extracting TODOs: ${e.message}" }
        }
        
        return todos
    }
    
    /**
     * Checks if a file is relevant for TODO extraction.
     */
    private fun isRelevantFile(path: Path): Boolean {
        val fileName = path.fileName.toString().lowercase()
        val extension = fileName.substringAfterLast('.', "")
        
        // Common source code file extensions
        val relevantExtensions = setOf(
            "java", "kt", "kts", "groovy", "scala", "js", "ts", "jsx", "tsx",
            "py", "rb", "php", "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift",
            "html", "xml", "json", "yaml", "yml", "md", "txt"
        )
        
        return extension in relevantExtensions
    }
    
    /**
     * Extracts TODOs from a file.
     */
    private fun extractTodosFromFile(content: String, filePath: String): List<Todo> {
        val todos = mutableListOf<Todo>()
        val lines = content.lines()
        
        // Process single-line TODOs
        val singleLineMatcher = singleLineTodoPattern.matcher(content)
        while (singleLineMatcher.find()) {
            val type = singleLineMatcher.group(1)
            val text = singleLineMatcher.group(2).trim()
            val lineNumber = getLineNumber(content, singleLineMatcher.start())
            
            todos.add(
                Todo(
                    type = TodoType.valueOf(type.uppercase()),
                    content = "$type: $text",
                    filePath = filePath,
                    lineNumber = lineNumber
                )
            )
        }
        
        // Process multi-line TODOs
        val multiLineMatcher = multiLineTodoPattern.matcher(content)
        while (multiLineMatcher.find()) {
            val type = multiLineMatcher.group(1)
            val text = multiLineMatcher.group(2).trim()
            val lineNumber = getLineNumber(content, multiLineMatcher.start())
            
            todos.add(
                Todo(
                    type = TodoType.valueOf(type.uppercase()),
                    content = "$type: $text",
                    filePath = filePath,
                    lineNumber = lineNumber
                )
            )
        }
        
        // Process Javadoc TODOs
        val javadocMatcher = javadocTodoPattern.matcher(content)
        while (javadocMatcher.find()) {
            val type = javadocMatcher.group(1)
            val text = javadocMatcher.group(2).trim()
            val lineNumber = getLineNumber(content, javadocMatcher.start())
            
            todos.add(
                Todo(
                    type = TodoType.valueOf(type.uppercase()),
                    content = "$type: $text",
                    filePath = filePath,
                    lineNumber = lineNumber
                )
            )
        }
        
        // Process @deprecated annotations
        val deprecatedMatcher = deprecatedPattern.matcher(content)
        while (deprecatedMatcher.find()) {
            val text = deprecatedMatcher.group(1).trim()
            val lineNumber = getLineNumber(content, deprecatedMatcher.start())
            
            todos.add(
                Todo(
                    type = TodoType.DEPRECATED,
                    content = "DEPRECATED: $text",
                    filePath = filePath,
                    lineNumber = lineNumber
                )
            )
        }
        
        return todos
    }
    
    /**
     * Gets the line number for a position in the content.
     */
    private fun getLineNumber(content: String, position: Int): Int {
        var lineNumber = 1
        for (i in 0 until position) {
            if (content[i] == '\n') {
                lineNumber++
            }
        }
        return lineNumber
    }
}

/**
 * Represents a TODO item found in the codebase.
 */
data class Todo(
    val type: TodoType,
    val content: String,
    val filePath: String,
    val lineNumber: Int
)

/**
 * Types of TODOs.
 */
enum class TodoType {
    TODO, FIXME, XXX, HACK, BUG, NOTE, DEPRECATED
}