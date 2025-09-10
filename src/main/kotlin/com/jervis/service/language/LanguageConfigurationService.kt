package com.jervis.service.language

import com.jervis.domain.language.CommunicationPlatform
import com.jervis.domain.language.Language
import com.jervis.domain.language.PlatformLanguageConfiguration
import com.jervis.domain.language.PlatformLanguageSettings
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Service for managing language configuration across clients and projects.
 */
@Service
class LanguageConfigurationService(
    private val clientRepository: ClientMongoRepository,
    private val projectRepository: ProjectMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Get the effective language for a specific platform based on client and project configuration.
     * Priority: Project language > Client platform-specific language > Client default language > System default
     */
    suspend fun getEffectiveLanguage(
        clientId: ObjectId,
        projectId: ObjectId?,
        platform: CommunicationPlatform,
    ): Language =
        withContext(Dispatchers.IO) {
            try {
                val client = clientRepository.findById(clientId)
                val project = projectId?.let { projectRepository.findById(it) }

                // Priority 1: Project communication language
                if (project != null) {
                    return@withContext project.communicationLanguage
                }

                // Priority 2: Client platform-specific language
                if (client != null) {
                    val platformLanguage = client.platformLanguageConfiguration.getLanguageForPlatform(platform)
                    if (platformLanguage != client.platformLanguageConfiguration.defaultLanguage) {
                        return@withContext platformLanguage
                    }

                    // Priority 3: Client default language
                    return@withContext client.defaultLanguage
                }

                // Priority 4: System default
                Language.getDefault()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to get effective language for client=$clientId, project=$projectId, platform=$platform" }
                Language.getDefault()
            }
        }

    /**
     * Update platform language configuration for a client.
     */
    suspend fun updateClientPlatformLanguage(
        clientId: ObjectId,
        platform: CommunicationPlatform,
        language: Language,
        useAutoDetection: Boolean = false,
        customSettings: Map<String, String> = emptyMap(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val client = clientRepository.findById(clientId) ?: return@withContext false

                val updatedSettings =
                    PlatformLanguageSettings(
                        platform = platform,
                        language = language,
                        useAutoDetection = useAutoDetection,
                        customSettings = customSettings,
                    )

                val updatedPlatformSettings = client.platformLanguageConfiguration.platformSettings.toMutableMap()
                updatedPlatformSettings[platform] = updatedSettings

                val updatedConfiguration =
                    client.platformLanguageConfiguration.copy(
                        platformSettings = updatedPlatformSettings,
                    )

                val updatedClient =
                    client.copy(
                        platformLanguageConfiguration = updatedConfiguration,
                        updatedAt = java.time.Instant.now(),
                    )

                clientRepository.save(updatedClient)
                logger.info { "Updated platform language configuration for client=$clientId, platform=$platform, language=$language" }
                true
            } catch (e: Exception) {
                logger.error(e) { "Failed to update platform language for client=$clientId, platform=$platform" }
                false
            }
        }

    /**
     * Update the communication language for a project.
     */
    suspend fun updateProjectLanguage(
        projectId: ObjectId,
        language: Language,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val project = projectRepository.findById(projectId) ?: return@withContext false

                val updatedProject =
                    project.copy(
                        communicationLanguage = language,
                        updatedAt = java.time.Instant.now(),
                    )

                projectRepository.save(updatedProject)
                logger.info { "Updated communication language for project=$projectId, language=$language" }
                true
            } catch (e: Exception) {
                logger.error(e) { "Failed to update project language for project=$projectId" }
                false
            }
        }

    /**
     * Get all platform language settings for a client.
     */
    suspend fun getClientPlatformLanguageSettings(clientId: ObjectId): PlatformLanguageConfiguration? =
        withContext(Dispatchers.IO) {
            try {
                clientRepository.findById(clientId)?.platformLanguageConfiguration
            } catch (e: Exception) {
                logger.warn(e) { "Failed to get platform language settings for client=$clientId" }
                null
            }
        }

    /**
     * Initialize default platform language configuration for common platforms.
     */
    suspend fun initializeDefaultPlatformConfiguration(
        clientId: ObjectId,
        defaultLanguage: Language = Language.ENGLISH,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val client = clientRepository.findById(clientId) ?: return@withContext false

                val commonPlatforms =
                    listOf(
                        CommunicationPlatform.GIT,
                        CommunicationPlatform.GITHUB,
                        CommunicationPlatform.TEAMS,
                        CommunicationPlatform.SLACK,
                        CommunicationPlatform.EMAIL,
                        CommunicationPlatform.CONFLUENCE,
                        CommunicationPlatform.JIRA,
                    )

                val platformSettings =
                    commonPlatforms.associate { platform ->
                        platform to
                            PlatformLanguageSettings(
                                platform = platform,
                                language = defaultLanguage,
                                useAutoDetection = false,
                            )
                    }

                val configuration =
                    PlatformLanguageConfiguration(
                        defaultLanguage = defaultLanguage,
                        platformSettings = platformSettings,
                    )

                val updatedClient =
                    client.copy(
                        defaultLanguage = defaultLanguage,
                        platformLanguageConfiguration = configuration,
                        updatedAt = java.time.Instant.now(),
                    )

                clientRepository.save(updatedClient)
                logger.info { "Initialized default platform configuration for client=$clientId with language=$defaultLanguage" }
                true
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize default platform configuration for client=$clientId" }
                false
            }
        }
}
