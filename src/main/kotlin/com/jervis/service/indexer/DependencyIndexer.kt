package com.jervis.service.indexer

import com.jervis.domain.dependency.Dependency
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.dependency.DependencyService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service responsible for indexing dependencies in a project.
 * This service is separate from the project index service and handles only dependency indexing.
 */
@Service
class DependencyIndexer(
    private val dependencyAnalyzer: DependencyAnalyzer,
    private val dependencyService: DependencyService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Index dependencies for a project.
     * This method analyzes dependencies, generates descriptions, and stores them in MongoDB and vector database.
     *
     * @param project The project to index dependencies for
     * @return The number of dependencies indexed
     */
    suspend fun indexDependencies(project: ProjectDocument): Int {
        logger.info { "Indexing dependencies for project: ${project.name}" }

        // Analyze dependencies using the DependencyAnalyzer
        val dependencies = dependencyAnalyzer.analyzeDependencies(project)

        if (dependencies.isEmpty()) {
            logger.info { "No dependencies found in project: ${project.name}" }
            return 0
        }

        logger.info { "Found ${dependencies.size} dependencies in project: ${project.name}" }

        // Store dependencies in MongoDB and vector database
        storeDependencies(project, dependencies)

        return dependencies.size
    }

    /**
     * Store dependencies in MongoDB and vector database.
     *
     * @param project The project
     * @param dependencies The list of dependencies
     */
    private suspend fun storeDependencies(
        project: ProjectDocument,
        dependencies: List<Dependency>,
    ) {
        val projectId = project.id ?: return

        // Generate dependency description using LLM
        val dependencyDescription = dependencyService.generateDependencyDescription(dependencies)

        // Store individual dependencies in MongoDB and vector database
        dependencies.map { dependency ->
            dependencyService.storeDependency(projectId, dependency)
        }

        // Store dependency description in MongoDB and vector database
        dependencyService.storeDependencyDescription(
            projectId,
            dependencyDescription,
            dependencies,
        )

        // Store dependency list in MongoDB
        dependencyService.storeDependencyList(
            projectId,
            dependencies,
            dependencyDescription,
        )

        logger.info { "Stored all dependencies for project: ${project.name}" }

        // Verify storage
        val verified = dependencyService.verifyDependencyStorage(projectId)
        if (!verified) {
            logger.warn { "Dependency storage verification failed for project: ${project.name}" }
        }
    }
}
