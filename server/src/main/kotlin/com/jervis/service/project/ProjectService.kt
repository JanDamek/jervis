package com.jervis.service.project

import com.jervis.dto.ProjectDto
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.git.GitRepositoryService
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ProjectService(
    private val projectRepository: ProjectMongoRepository,
    private val gitRepositoryService: GitRepositoryService,
    private val directoryStructureService: DirectoryStructureService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun getAllProjects(): List<ProjectDocument> = projectRepository.findAll().toList()

    suspend fun getDefaultProject(): ProjectDocument? = projectRepository.findByIsActiveIsTrue()

    suspend fun setActiveProject(project: ProjectDocument) {
        setDefaultProject(project)
    }

    suspend fun setDefaultProject(project: ProjectDocument) {
        val allProjects = projectRepository.findAll().toList()
        allProjects.forEach { existingProject ->
            if (existingProject.isActive && existingProject.id != project.id) {
                val updatedProject =
                    existingProject.copy(
                        isActive = false,
                        updatedAt = Instant.now(),
                    )
                projectRepository.save(updatedProject)
            }
        }

        // Set the default status for the selected project
        if (!project.isActive) {
            val updatedProject =
                project.copy(
                    isActive = true,
                    updatedAt = Instant.now(),
                )
            projectRepository.save(updatedProject)
        }
    }

    suspend fun saveProject(
        project: ProjectDocument,
        makeDefault: Boolean,
    ): ProjectDto {
        val existing = projectRepository.findById(project.id)
        val isNew = existing == null

        val savedProject =
            if (isNew) {
                val newProject = project.copy(createdAt = Instant.now(), updatedAt = Instant.now())
                projectRepository.save(newProject)
            } else {
                val mergedGitConfig =
                    when {
                        project.overrides.gitConfig != null && existing.overrides.gitConfig != null -> {
                            existing.overrides.gitConfig.copy(
                                gitUserName =
                                    project.overrides.gitConfig.gitUserName
                                        ?: existing.overrides.gitConfig.gitUserName,
                                gitUserEmail =
                                    project.overrides.gitConfig.gitUserEmail
                                        ?: existing.overrides.gitConfig.gitUserEmail,
                                commitMessageTemplate =
                                    project.overrides.gitConfig.commitMessageTemplate
                                        ?: existing.overrides.gitConfig.commitMessageTemplate,
                                requireGpgSign = project.overrides.gitConfig.requireGpgSign,
                                gpgKeyId =
                                    project.overrides.gitConfig.gpgKeyId
                                        ?: existing.overrides.gitConfig.gpgKeyId,
                                requireLinearHistory = project.overrides.gitConfig.requireLinearHistory,
                                conventionalCommits = project.overrides.gitConfig.conventionalCommits,
                                commitRules =
                                    if (project.overrides.gitConfig.commitRules
                                            .isNotEmpty()
                                    ) {
                                        project.overrides.gitConfig.commitRules
                                    } else {
                                        existing.overrides.gitConfig.commitRules
                                    },
                                sshPrivateKey =
                                    project.overrides.gitConfig.sshPrivateKey
                                        ?: existing.overrides.gitConfig.sshPrivateKey,
                                sshPublicKey =
                                    project.overrides.gitConfig.sshPublicKey
                                        ?: existing.overrides.gitConfig.sshPublicKey,
                                sshPassphrase =
                                    project.overrides.gitConfig.sshPassphrase
                                        ?: existing.overrides.gitConfig.sshPassphrase,
                                httpsToken =
                                    project.overrides.gitConfig.httpsToken
                                        ?: existing.overrides.gitConfig.httpsToken,
                                httpsUsername =
                                    project.overrides.gitConfig.httpsUsername
                                        ?: existing.overrides.gitConfig.httpsUsername,
                                httpsPassword =
                                    project.overrides.gitConfig.httpsPassword
                                        ?: existing.overrides.gitConfig.httpsPassword,
                                gpgPrivateKey =
                                    project.overrides.gitConfig.gpgPrivateKey
                                        ?: existing.overrides.gitConfig.gpgPrivateKey,
                                gpgPublicKey =
                                    project.overrides.gitConfig.gpgPublicKey
                                        ?: existing.overrides.gitConfig.gpgPublicKey,
                                gpgPassphrase =
                                    project.overrides.gitConfig.gpgPassphrase
                                        ?: existing.overrides.gitConfig.gpgPassphrase,
                            )
                        }

                        project.overrides.gitConfig != null -> project.overrides.gitConfig
                        else -> existing.overrides.gitConfig
                    }

                val mergedOverrides = project.overrides.copy(gitConfig = mergedGitConfig)

                val updatedProject =
                    project.copy(
                        createdAt = existing.createdAt,
                        updatedAt = Instant.now(),
                        overrides = mergedOverrides,
                    )
                projectRepository.save(updatedProject)
            }

        if (makeDefault) {
            setDefaultProject(savedProject)
        }

        directoryStructureService.ensureProjectDirectories(savedProject.clientId, savedProject.id)

        if (isNew) {
            logger.info { "Created new project: ${savedProject.name}" }
        } else {
            logger.info { "Updated project: ${savedProject.name}" }
        }

        return savedProject.toDto()
    }

    suspend fun deleteProject(project: ProjectDto) {
        val projectDoc = project.toDocument()
        if (projectDoc.isActive) {
            logger.warn { "Attempting to delete default project: ${projectDoc.name}" }
        }

        projectRepository.delete(projectDoc)
        logger.info { "Deleted project: ${projectDoc.name}" }
    }

    suspend fun getProjectByName(name: String?): ProjectDocument =
        requireNotNull(name?.let { projectRepository.findByName(it) }) {
            "Project not found with name: $name"
        }
}
