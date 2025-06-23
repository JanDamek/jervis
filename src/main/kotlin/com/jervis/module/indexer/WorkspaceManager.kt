package com.jervis.module.indexer

import com.jervis.entity.Project
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Service responsible for managing workspaces for projects.
 * Creates and maintains a local workspace for each project where JERVIS can
 * perform operations like git checkout, branch switching, etc.
 */
@Service
class WorkspaceManager(
    @Value("\${jervis.workspace.root:~/.jervis/projects}") private val workspaceRootPath: String
) {
    private val logger = KotlinLogging.logger {}
    
    /**
     * Sets up a workspace for a project.
     * If the workspace already exists, it ensures it's up to date.
     * If it doesn't exist, it creates a new workspace by cloning the project.
     * 
     * @param project The project to set up a workspace for
     * @return The path to the workspace
     */
    fun setupWorkspace(project: Project): Path {
        logger.info { "Setting up workspace for project: ${project.name}" }
        
        val workspaceRoot = expandPath(workspaceRootPath)
        val projectWorkspacePath = workspaceRoot.resolve(project.id.toString())
        
        // Create workspace root directory if it doesn't exist
        if (!Files.exists(workspaceRoot)) {
            Files.createDirectories(workspaceRoot)
            logger.info { "Created workspace root directory: $workspaceRoot" }
        }
        
        // Check if workspace already exists
        if (Files.exists(projectWorkspacePath)) {
            logger.info { "Workspace already exists for project: ${project.name}" }
            updateWorkspace(project, projectWorkspacePath)
        } else {
            logger.info { "Creating new workspace for project: ${project.name}" }
            createWorkspace(project, projectWorkspacePath)
        }
        
        return projectWorkspacePath
    }
    
    /**
     * Creates a new workspace for a project by cloning or copying the project.
     */
    private fun createWorkspace(project: Project, workspacePath: Path) {
        val projectPath = Paths.get(project.path)
        
        // Check if the project is a Git repository
        val gitDir = projectPath.resolve(".git")
        if (Files.exists(gitDir) && Files.isDirectory(gitDir)) {
            // Clone the repository
            cloneRepository(project, workspacePath)
        } else {
            // Copy the project files
            copyProject(project, workspacePath)
        }
    }
    
    /**
     * Updates an existing workspace to ensure it's in sync with the project.
     */
    private fun updateWorkspace(project: Project, workspacePath: Path) {
        val gitDir = workspacePath.resolve(".git")
        if (Files.exists(gitDir) && Files.isDirectory(gitDir)) {
            // Pull the latest changes
            pullRepository(workspacePath)
        } else {
            // Re-copy the project files
            deleteWorkspace(workspacePath)
            copyProject(project, workspacePath)
        }
    }
    
    /**
     * Clones a Git repository to the workspace.
     */
    private fun cloneRepository(project: Project, workspacePath: Path) {
        try {
            val projectPath = Paths.get(project.path)
            
            // Use ProcessBuilder to execute git clone
            val process = ProcessBuilder()
                .directory(workspacePath.parent.toFile())
                .command("git", "clone", projectPath.toAbsolutePath().toString(), workspacePath.fileName.toString())
                .start()
            
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                logger.info { "Successfully cloned repository for project: ${project.name}" }
            } else {
                val error = process.errorStream.bufferedReader().readText()
                logger.error { "Failed to clone repository: $error" }
                
                // Fallback to copying the project files
                copyProject(project, workspacePath)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error cloning repository: ${e.message}" }
            
            // Fallback to copying the project files
            copyProject(project, workspacePath)
        }
    }
    
    /**
     * Pulls the latest changes from a Git repository.
     */
    private fun pullRepository(workspacePath: Path) {
        try {
            // Use ProcessBuilder to execute git pull
            val process = ProcessBuilder()
                .directory(workspacePath.toFile())
                .command("git", "pull")
                .start()
            
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                logger.info { "Successfully pulled latest changes for workspace: ${workspacePath.fileName}" }
            } else {
                val error = process.errorStream.bufferedReader().readText()
                logger.error { "Failed to pull repository: $error" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error pulling repository: ${e.message}" }
        }
    }
    
    /**
     * Copies a project to the workspace.
     */
    private fun copyProject(project: Project, workspacePath: Path) {
        try {
            val projectPath = Paths.get(project.path)
            
            // Create the workspace directory
            Files.createDirectories(workspacePath)
            
            // Copy all files and directories
            Files.walk(projectPath)
                .filter { path -> !path.toString().contains("/.git/") } // Skip .git directory
                .forEach { source ->
                    val relativePath = projectPath.relativize(source)
                    val destination = workspacePath.resolve(relativePath)
                    
                    if (Files.isDirectory(source)) {
                        if (!Files.exists(destination)) {
                            Files.createDirectories(destination)
                        }
                    } else {
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            
            logger.info { "Successfully copied project files to workspace: ${workspacePath.fileName}" }
        } catch (e: Exception) {
            logger.error(e) { "Error copying project files: ${e.message}" }
        }
    }
    
    /**
     * Deletes a workspace.
     */
    private fun deleteWorkspace(workspacePath: Path) {
        try {
            if (Files.exists(workspacePath)) {
                Files.walk(workspacePath)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.delete(it) }
                
                logger.info { "Deleted workspace: ${workspacePath.fileName}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error deleting workspace: ${e.message}" }
        }
    }
    
    /**
     * Expands a path that might contain ~ to represent the user's home directory.
     */
    private fun expandPath(path: String): Path {
        return if (path.startsWith("~")) {
            val userHome = System.getProperty("user.home")
            Paths.get(userHome, path.substring(1))
        } else {
            Paths.get(path)
        }
    }
}