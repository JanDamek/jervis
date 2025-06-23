package com.jervis.module.indexer

import com.jervis.entity.Project
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

/**
 * Service responsible for analyzing dependencies between classes and packages in a project.
 * Uses regex-based analysis to identify import statements and class references.
 */
@Service
class DependencyAnalyzer {
    private val logger = KotlinLogging.logger {}

    // Regex patterns for import statements
    private val javaImportPattern = Pattern.compile("import\\s+([\\w.]+)(?:\\s*;)?")
    private val kotlinImportPattern = Pattern.compile("import\\s+([\\w.]+)(?:\\s*\n)?")

    /**
     * Analyzes dependencies in a project and returns a list of dependencies.
     * 
     * @param project The project to analyze
     * @return List of dependencies between classes
     */
    fun analyzeDependencies(project: Project): List<Dependency> {
        logger.info { "Analyzing dependencies for project: ${project.name}" }
        val dependencies = mutableListOf<Dependency>()

        try {
            val projectPath = Paths.get(project.path)

            // Walk through all files in the project
            Files.walk(projectPath)
                .filter { Files.isRegularFile(it) }
                .filter { isSourceFile(it) }
                .forEach { filePath ->
                    try {
                        val relativePath = projectPath.relativize(filePath).toString()
                        val fileContent = String(Files.readAllBytes(filePath), Charsets.UTF_8)
                        val className = extractClassName(filePath, fileContent)

                        if (className != null) {
                            // Extract imports
                            val imports = extractImports(fileContent)

                            // Add dependencies
                            imports.forEach { importedClass ->
                                dependencies.add(
                                    Dependency(
                                        sourceClass = className,
                                        targetClass = importedClass,
                                        type = DependencyType.IMPORT,
                                        sourceFile = relativePath
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Error analyzing dependencies in file: ${filePath.fileName}" }
                    }
                }

            logger.info { "Found ${dependencies.size} dependencies in project: ${project.name}" }
        } catch (e: Exception) {
            logger.error(e) { "Error analyzing dependencies: ${e.message}" }
        }

        return dependencies
    }

    /**
     * Checks if a file is a source file (Java or Kotlin).
     */
    private fun isSourceFile(path: Path): Boolean {
        val fileName = path.fileName.toString().lowercase()
        return fileName.endsWith(".java") || fileName.endsWith(".kt") || fileName.endsWith(".kts")
    }

    /**
     * Extracts the class name from a file.
     */
    private fun extractClassName(filePath: Path, content: String): String? {
        val fileName = filePath.fileName.toString()

        // For Java files
        if (fileName.endsWith(".java")) {
            val packagePattern = Pattern.compile("package\\s+([\\w.]+)\\s*;")
            val classPattern = Pattern.compile("(?:public\\s+)?(?:class|interface|enum)\\s+(\\w+)")

            val packageMatcher = packagePattern.matcher(content)
            val packageName = if (packageMatcher.find()) packageMatcher.group(1) else ""

            val classMatcher = classPattern.matcher(content)
            return if (classMatcher.find()) {
                if (packageName.isNotEmpty()) "$packageName.${classMatcher.group(1)}" else classMatcher.group(1)
            } else null
        }

        // For Kotlin files
        if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) {
            val packagePattern = Pattern.compile("package\\s+([\\w.]+)")
            val classPattern = Pattern.compile("(?:class|interface|object|enum class)\\s+(\\w+)")

            val packageMatcher = packagePattern.matcher(content)
            val packageName = if (packageMatcher.find()) packageMatcher.group(1) else ""

            val classMatcher = classPattern.matcher(content)
            return if (classMatcher.find()) {
                if (packageName.isNotEmpty()) "$packageName.${classMatcher.group(1)}" else classMatcher.group(1)
            } else null
        }

        return null
    }

    /**
     * Extracts import statements from file content.
     */
    private fun extractImports(content: String): List<String> {
        val imports = mutableListOf<String>()

        // Match Java imports
        val javaMatcher = javaImportPattern.matcher(content)
        while (javaMatcher.find()) {
            imports.add(javaMatcher.group(1))
        }

        // Match Kotlin imports
        val kotlinMatcher = kotlinImportPattern.matcher(content)
        while (kotlinMatcher.find()) {
            imports.add(kotlinMatcher.group(1))
        }

        return imports
    }
}

/**
 * Represents a dependency between two classes.
 */
data class Dependency(
    val sourceClass: String,
    val targetClass: String,
    val type: DependencyType,
    val sourceFile: String
)

/**
 * Types of dependencies between classes.
 */
enum class DependencyType {
    IMPORT, EXTENDS, IMPLEMENTS, USES
}
