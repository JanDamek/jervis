package com.jervis.service.gitwatcher

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.indexer.IndexerService
import com.jervis.service.project.ProjectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for monitoring Git repositories for changes.
 * This service periodically checks for new commits in the repositories
 * and triggers indexing of new or modified files.
 * It also monitors the filesystem for changes in real-time.
 */
@Service
class GitWatcherService(
    private val projectService: ProjectService,
    private val gitClient: GitClient,
    private val indexerService: IndexerService,
) : CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val logger = KotlinLogging.logger {}

    // Map to store the last commit timestamp for each project
    private val lastCommitTimestamps = ConcurrentHashMap<ObjectId, Instant>()

    // Map to store filesystem watchers for each project
    private val fileWatchers = ConcurrentHashMap<ObjectId, WatchService>()

    /**
     * Initialize the service by recording the current HEAD commit for each project
     * and setting up filesystem watchers
     */
    suspend fun initialize() {
        projectService.getAllProjectsBlocking().forEach { project ->
            project.id?.let { projectId ->
                initializeGitWatcher(project, projectId)
                initializeFileSystemWatcher(project, projectId)
            }
        }
    }

    /**
     * Initialize Git watcher for a project
     */
    private fun initializeGitWatcher(
        project: ProjectDocument,
        projectId: ObjectId,
    ) {
        val gitDir = File(project.path, ".git")
        if (gitDir.exists() && gitDir.isDirectory) {
            val lastCommitTime = gitClient.getLastCommitTime(project.path)
            lastCommitTimestamps[projectId] = lastCommitTime
            logger.info { "Initialized Git watcher for project ${project.name} with last commit time: $lastCommitTime" }
        }
    }

    /**
     * Initialize filesystem watcher for a project
     */
    private suspend fun initializeFileSystemWatcher(
        project: ProjectDocument,
        projectId: ObjectId,
    ) {
        project.path.let { path ->
            try {
                val watchService = FileSystems.getDefault().newWatchService()
                val projectPath = Paths.get(path)

                // Register the project directory for file creation, modification, and deletion events
                projectPath.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )

                // Also register subdirectories
                Files
                    .walk(projectPath)
                    .filter { Files.isDirectory(it) }
                    .filter { !it.toString().contains("/.git/") } // Skip .git directory
                    .forEach { dir ->
                        try {
                            dir.register(
                                watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE,
                            )
                        } catch (e: Exception) {
                            logger.warn { "Could not register watcher for directory $dir: ${e.message}" }
                        }
                    }

                fileWatchers[projectId] = watchService

                launch {
                    monitorFileSystem(project, projectId, watchService)
                }

                logger.info { "Initialized filesystem watcher for project ${project.name}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize filesystem watcher for project ${project.name}: ${e.message}" }
            }
        }
    }

    /**
     * Monitor filesystem events for a project
     */
    private suspend fun monitorFileSystem(
        project: ProjectDocument,
        projectId: ObjectId,
        watchService: WatchService,
    ) {
        val projectPath = Paths.get(project.path)

        try {
            while (true) {
                val key: WatchKey = watchService.take() // This blocks until events are available

                for (event in key.pollEvents()) {
                    val kind = event.kind()

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue
                    }

                    // Get the filename from the event
                    val ev = event as WatchEvent<Path>
                    val fileName = ev.context()

                    // Get the directory that the event came from
                    val dir = key.watchable() as Path
                    val fullPath = dir.resolve(fileName)

                    // Skip directories and non-relevant files
                    if (Files.isDirectory(fullPath) || !indexerService.isRelevantFile(fullPath)) {
                        continue
                    }

                    logger.debug { "Filesystem event: $kind for file $fileName" }

                    when (kind) {
                        StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY -> {
                            // Get commit information for the file
                            val relativePath = projectPath.relativize(fullPath).toString()
                            val commitInfo = gitClient.getFileCommitInfo(project.path, relativePath)

                            // Index the file with commit information
                            if (commitInfo != null) {
                                indexerService.indexFileWithCommitInfo(
                                    projectId,
                                    projectPath,
                                    fullPath,
                                    commitInfo.id,
                                    commitInfo.authorName,
                                    commitInfo.time,
                                )
                            } else {
                                // If no commit info (e.g., new file not yet committed), index without it
                                indexerService.indexFile(projectId, projectPath, fullPath)
                            }
                        }
                        // For deletion events, we might want to remove the file from the index
                        // This would require additional functionality in IndexerService
                    }
                }

                // Reset the key to receive further events
                val valid = key.reset()
                if (!valid) {
                    logger.warn { "Watch key no longer valid for project ${project.name}" }
                    break
                }
            }
        } catch (e: InterruptedException) {
            logger.info { "Filesystem watcher for project ${project.name} was interrupted" }
        } catch (e: Exception) {
            logger.error(e) { "Error in filesystem watcher for project ${project.name}: ${e.message}" }
        }
    }

    /**
     * Scheduled task that checks for new commits in all projects
     */
    @Scheduled(fixedDelay = 60000) // Check every minute
    suspend fun checkForNewCommits() {
        projectService.getAllProjectsBlocking().forEach { project ->
            checkProjectForChanges(project)
        }
    }

    /**
     * Check a specific project for new commits
     */
    suspend fun checkProjectForChanges(project: ProjectDocument) {
        project.id?.let { projectId ->
            val gitDir = File(project.path, ".git")
            if (!gitDir.exists() || !gitDir.isDirectory) {
                return
            }

            val currentCommitInfo = gitClient.getLastCommitInfo(project.path)
            if (currentCommitInfo == null) {
                logger.warn { "Could not get commit info for project ${project.name}" }
                return
            }

            val currentLastCommitTime = currentCommitInfo.time
            val previousLastCommitTime = lastCommitTimestamps[projectId]

            if (previousLastCommitTime == null || currentLastCommitTime.isAfter(previousLastCommitTime)) {
                // New commits detected
                val changedFiles = gitClient.getChangedFilesSince(project.path, previousLastCommitTime)

                if (changedFiles.isNotEmpty()) {
                    logger.info { "Detected ${changedFiles.size} changed files in project ${project.name}" }

                    // Index each changed file with commit information
                    val projectPath = Paths.get(project.path)
                    changedFiles.forEach { filePath ->
                        val fullPath = projectPath.resolve(filePath)

                        // Skip directories and non-relevant files
                        if (Files.exists(fullPath) &&
                            !Files.isDirectory(fullPath) &&
                            indexerService.isRelevantFile(
                                fullPath,
                            )
                        ) {
                            // Get commit information for the specific file
                            val fileCommitInfo = gitClient.getFileCommitInfo(project.path, filePath)

                            if (fileCommitInfo != null) {
                                logger.debug { "Indexing changed file: $filePath with commit ${fileCommitInfo.id}" }
                                indexerService.indexFileWithCommitInfo(
                                    projectId,
                                    projectPath,
                                    fullPath,
                                    fileCommitInfo.id,
                                    fileCommitInfo.authorName,
                                    fileCommitInfo.time,
                                )
                            }
                        }
                    }
                }

                // Update the last commit timestamp
                lastCommitTimestamps[projectId] = currentLastCommitTime
            }
        }
    }
}
