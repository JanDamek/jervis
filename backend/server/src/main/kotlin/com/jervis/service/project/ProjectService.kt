package com.jervis.service.project

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.MergeConflictDto
import com.jervis.dto.MergeExecuteDto
import com.jervis.dto.MergeMigrationDto
import com.jervis.dto.MergePreviewDto
import com.jervis.dto.MergeResolutionDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ProjectDocument
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.entity.SystemConfigDocument
import com.jervis.repository.ProjectRepository
import com.jervis.repository.SystemConfigRepository
import com.jervis.service.indexing.git.GitRepositoryService
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val systemConfigRepository: SystemConfigRepository,
    private val gitRepositoryService: GitRepositoryService,
    private val directoryStructureService: DirectoryStructureService,
    private val applicationEventPublisher: org.springframework.context.ApplicationEventPublisher,
    private val knowledgeClient: com.jervis.configuration.KnowledgeServiceRestClient,
    private val mongoTemplate: org.springframework.data.mongodb.core.ReactiveMongoTemplate,
    private val cascadeLlm: com.jervis.configuration.CascadeLlmClient,
) {
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Retry any KB retag-group operations that were interrupted by server crash.
     * Called from BackgroundEngine startup.
     */
    suspend fun retryPendingRetags() {
        val pending = projectRepository.findByPendingRetagGroupIdIsNotNull().toList()
        if (pending.isEmpty()) return
        logger.info { "Found ${pending.size} projects with pending KB retag-group, retrying..." }
        for (project in pending) {
            val newGroupId = project.pendingRetagGroupId?.takeIf { it != "__null__" }
            bgScope.launch {
                val success = knowledgeClient.retagGroupId(
                    projectId = project.id.toString(),
                    newGroupId = newGroupId,
                )
                if (success) {
                    projectRepository.findById(project.id)?.let {
                        projectRepository.save(it.copy(pendingRetagGroupId = null))
                    }
                    logger.info { "Recovered pending retag for project ${project.name}" }
                } else {
                    logger.warn { "Failed to recover pending retag for project ${project.name}, will retry on next restart" }
                }
            }
        }
    }

    suspend fun getAllProjects(): List<ProjectDocument> = projectRepository.findAll().toList()

    suspend fun listProjectsForClient(clientId: ClientId): List<ProjectDocument> = projectRepository.findByClientId(clientId).toList()

    suspend fun saveProject(project: ProjectDocument): ProjectDto {
        val existing = getProjectByIdOrNull(project.id)
        val isNew = existing == null

        val merged =
            existing?.copy(
                name = project.name,
                description = project.description,
                groupId = project.groupId,
                communicationLanguageEnum = project.communicationLanguageEnum,
                buildConfig = project.buildConfig,
                cloudModelPolicy = project.cloudModelPolicy,
                gitCommitConfig = project.gitCommitConfig,
                connectionCapabilities = project.connectionCapabilities,
                resources = project.resources,
                resourceLinks = project.resourceLinks,
            )
                ?: project

        val savedProject = projectRepository.save(merged)

        directoryStructureService.ensureProjectDirectories(savedProject.clientId, savedProject.id)

        if (isNew) {
            logger.info { "Created new project: ${savedProject.name}" }
        } else {
            logger.info { "Updated project: ${savedProject.name}" }

            // Retag KB items when project group changes (with crash recovery)
            if (existing.groupId != project.groupId) {
                val newGroupIdStr = savedProject.groupId?.toString()
                logger.info { "Project group changed: ${existing.groupId} → ${project.groupId}, retagging KB items" }
                // Set pending flag for crash recovery before calling KB
                projectRepository.save(savedProject.copy(pendingRetagGroupId = newGroupIdStr ?: "__null__"))
                bgScope.launch {
                    val success = knowledgeClient.retagGroupId(
                        projectId = savedProject.id.toString(),
                        newGroupId = newGroupIdStr,
                    )
                    if (success) {
                        // Clear pending flag on success
                        projectRepository.findById(savedProject.id)?.let {
                            projectRepository.save(it.copy(pendingRetagGroupId = null))
                        }
                    }
                    // On failure, flag remains — startup recovery will retry
                }
            }
        }

        // Trigger async repo sync if project has REPOSITORY resources (for indexing)
        val hasRepos = savedProject.resources.any { it.capability == ConnectionCapability.REPOSITORY }
        if (hasRepos) {
            bgScope.launch {
                try {
                    gitRepositoryService.syncProjectRepositories(savedProject)
                } catch (e: Exception) {
                    logger.error(e) { "Background repo sync failed for project ${savedProject.name}" }
                }
            }

            // Trigger workspace initialization for agent — always unless already CLONING
            // READY → refresh (git fetch), null → clone, CLONE_FAILED_* → retry, NOT_NEEDED → re-check
            if (savedProject.workspaceStatus != com.jervis.entity.WorkspaceStatus.CLONING) {
                // Reset retry state for failed clones so init starts fresh (no stale backoff)
                val resetProject = if (savedProject.workspaceStatus?.isCloneFailed == true) {
                    projectRepository.save(
                        savedProject.copy(
                            workspaceStatus = null,
                            workspaceRetryCount = 0,
                            nextWorkspaceRetryAt = null,
                            lastWorkspaceError = null,
                        ),
                    )
                } else {
                    savedProject
                }
                logger.info { "Publishing workspace init event for project ${resetProject.name} (current status: ${savedProject.workspaceStatus})" }
                applicationEventPublisher.publishEvent(ProjectWorkspaceInitEvent(resetProject))
            } else {
                logger.info { "Project ${savedProject.name} workspace is CLONING — skipping re-trigger" }
            }
        }

        return savedProject.toDto()
    }

    suspend fun deleteProject(project: ProjectDto) {
        val projectDoc = project.toDocument()
        projectRepository.delete(projectDoc)
        logger.info { "Deleted project: ${projectDoc.name}" }
    }

    /** Preview merge: detect conflicts between source and target. */
    suspend fun previewMerge(sourceId: ProjectId, targetId: ProjectId): MergePreviewDto {
        val source = getProjectById(sourceId)
        val target = getProjectById(targetId)
        require(source.clientId == target.clientId) { "Cannot merge projects from different clients" }

        val conflicts = mutableListOf<MergeConflictDto>()
        val autoMigrate = mutableListOf<MergeMigrationDto>()

        // Count documents per collection that will be auto-migrated
        val dataCols = listOf(
            "tasks", "meetings", "llm_costs", "error_logs", "user_corrections",
            "approval_queue", "git_commits",
            "teams_message_index", "slack_message_index", "discord_message_index",
            "email_message_index", "bugtracker_issues", "wiki_pages",
        )
        for (col in dataCols) {
            val query = org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(sourceId.toString()),
            )
            val count = mongoTemplate.count(query, col).awaitSingle().toInt()
            if (count > 0) autoMigrate.add(MergeMigrationDto(col, count))
        }

        // Detect conflicts: project description
        if (!source.description.isNullOrBlank() && !target.description.isNullOrBlank()
            && source.description != target.description
        ) {
            conflicts.add(MergeConflictDto(
                key = "description", label = "Popis projektu",
                sourceValue = source.description!!, targetValue = target.description!!,
                canMergeBoth = false, category = "TEXT",
            ))
        }

        // Detect conflicts: resources (git repos, issue trackers)
        val sourceResIds = source.resources.map { it.resourceIdentifier }.toSet()
        val targetResIds = target.resources.map { it.resourceIdentifier }.toSet()
        val newResources = source.resources.filter { it.resourceIdentifier !in targetResIds }
        if (newResources.isNotEmpty()) {
            conflicts.add(MergeConflictDto(
                key = "resources", label = "Zdroje (${newResources.size} novych)",
                sourceValue = newResources.joinToString(", ") { it.displayName.ifBlank { it.resourceIdentifier } },
                targetValue = target.resources.joinToString(", ") { it.displayName.ifBlank { it.resourceIdentifier } },
                canMergeBoth = true, category = "RESOURCE",
            ))
        }

        // Detect conflicts: guidelines (unique per clientId+projectId)
        val sourceGuidelines = mongoTemplate.find(
            org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(sourceId.toString()),
            ),
            org.bson.Document::class.java, "guidelines",
        ).collectList().awaitSingle()
        val targetGuidelines = mongoTemplate.find(
            org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(targetId.toString()),
            ),
            org.bson.Document::class.java, "guidelines",
        ).collectList().awaitSingle()
        if (sourceGuidelines.isNotEmpty() && targetGuidelines.isNotEmpty()) {
            val srcText = sourceGuidelines.firstOrNull()?.getString("content") ?: ""
            val tgtText = targetGuidelines.firstOrNull()?.getString("content") ?: ""
            if (srcText.isNotBlank() && tgtText.isNotBlank() && srcText != tgtText) {
                conflicts.add(MergeConflictDto(
                    key = "guidelines", label = "Smernice projektu",
                    sourceValue = srcText.take(200), targetValue = tgtText.take(200),
                    canMergeBoth = true, category = "TEXT",
                ))
            }
        }

        // Detect conflicts: agent preferences (unique per clientId+projectId+key)
        val sourcePrefs = mongoTemplate.find(
            org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(sourceId.toString()),
            ),
            org.bson.Document::class.java, "agent_preferences",
        ).collectList().awaitSingle()
        val targetPrefs = mongoTemplate.find(
            org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(targetId.toString()),
            ),
            org.bson.Document::class.java, "agent_preferences",
        ).collectList().awaitSingle()
        val targetPrefKeys = targetPrefs.mapNotNull { it.getString("key") }.toSet()
        for (srcPref in sourcePrefs) {
            val key = srcPref.getString("key") ?: continue
            if (key in targetPrefKeys) {
                val tgtPref = targetPrefs.first { it.getString("key") == key }
                val srcVal = srcPref.getString("value") ?: ""
                val tgtVal = tgtPref.getString("value") ?: ""
                if (srcVal != tgtVal) {
                    conflicts.add(MergeConflictDto(
                        key = "preference:$key", label = "Preference: $key",
                        sourceValue = srcVal, targetValue = tgtVal,
                        canMergeBoth = false, category = "SETTING",
                    ))
                }
            }
        }

        // AI-assisted merge for TEXT conflicts — generate suggested merged value
        val resolvedConflicts = conflicts.map { conflict ->
            if (conflict.category == "TEXT" && conflict.sourceValue.isNotBlank() && conflict.targetValue.isNotBlank()) {
                val aiMerged = try {
                    cascadeLlm.prompt(
                        prompt = "Merge these two project texts into one coherent version. " +
                            "Keep all unique information from both. Remove duplicates. " +
                            "Output ONLY the merged text, no explanation.\n\n" +
                            "Text A (${source.name}):\n${conflict.sourceValue}\n\n" +
                            "Text B (${target.name}):\n${conflict.targetValue}",
                        system = "You merge project configuration texts. Output only the merged result.",
                    )
                } catch (e: Exception) {
                    logger.debug { "AI merge suggestion failed for ${conflict.key}: ${e.message}" }
                    null
                }
                conflict.copy(aiMergedValue = aiMerged, canMergeBoth = true)
            } else {
                conflict
            }
        }

        return MergePreviewDto(
            sourceProject = source.name,
            targetProject = target.name,
            autoMigrate = autoMigrate,
            conflicts = resolvedConflicts,
        )
    }

    /** Execute merge with user-provided conflict resolutions. */
    suspend fun executeMerge(request: MergeExecuteDto) {
        val sourceId = ProjectId(org.bson.types.ObjectId(request.sourceProjectId))
        val targetId = ProjectId(org.bson.types.ObjectId(request.targetProjectId))
        val source = getProjectById(sourceId)
        val target = getProjectById(targetId)
        require(source.clientId == target.clientId) { "Cannot merge projects from different clients" }

        logger.info { "MERGE_START: ${source.name} → ${target.name} (${request.resolutions.size} resolutions)" }

        val resolutionMap = request.resolutions.associateBy { it.key }

        // Apply conflict resolutions before bulk migration
        // Description
        val descRes = resolutionMap["description"]
        if (descRes != null) {
            val newDesc = when (descRes.resolution) {
                "KEEP_SOURCE" -> source.description
                "KEEP_TARGET" -> target.description
                "CUSTOM" -> descRes.customValue
                else -> target.description
            }
            if (newDesc != null) {
                projectRepository.save(target.copy(description = newDesc))
            }
        }

        // Resources — MERGE_BOTH adds source resources to target
        val resRes = resolutionMap["resources"]
        if (resRes?.resolution == "MERGE_BOTH" || resRes?.resolution == "KEEP_SOURCE") {
            val targetResIds = target.resources.map { it.resourceIdentifier }.toSet()
            val newRes = source.resources.filter { it.resourceIdentifier !in targetResIds }
            if (newRes.isNotEmpty()) {
                val merged = target.copy(
                    resources = target.resources + newRes,
                    resourceLinks = target.resourceLinks + source.resourceLinks,
                )
                projectRepository.save(merged)
            }
        }

        // Guidelines — handle TEXT merge
        val guidelinesRes = resolutionMap["guidelines"]
        if (guidelinesRes != null) {
            when (guidelinesRes.resolution) {
                "KEEP_SOURCE" -> {
                    // Delete target guidelines, source will be migrated
                    mongoTemplate.remove(
                        org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(targetId.toString()),
                        ), "guidelines",
                    ).awaitSingle()
                }
                "KEEP_TARGET" -> {
                    // Delete source guidelines before migration
                    mongoTemplate.remove(
                        org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(sourceId.toString()),
                        ), "guidelines",
                    ).awaitSingle()
                }
                "MERGE_BOTH", "CUSTOM" -> {
                    val customContent = guidelinesRes.customValue ?: ""
                    // Update target with merged content, delete source
                    if (customContent.isNotBlank()) {
                        mongoTemplate.updateMulti(
                            org.springframework.data.mongodb.core.query.Query(
                                org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(targetId.toString()),
                            ),
                            org.springframework.data.mongodb.core.query.Update().set("content", customContent),
                            "guidelines",
                        ).awaitSingle()
                    }
                    mongoTemplate.remove(
                        org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(sourceId.toString()),
                        ), "guidelines",
                    ).awaitSingle()
                }
            }
        }

        // Preferences — apply per-key resolutions
        for ((key, res) in resolutionMap) {
            if (!key.startsWith("preference:")) continue
            val prefKey = key.removePrefix("preference:")
            when (res.resolution) {
                "KEEP_TARGET" -> {
                    // Delete source preference
                    mongoTemplate.remove(
                        org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria
                                .where("projectId").`is`(sourceId.toString())
                                .and("key").`is`(prefKey),
                        ), "agent_preferences",
                    ).awaitSingle()
                }
                "KEEP_SOURCE" -> {
                    // Delete target preference (source will be migrated)
                    mongoTemplate.remove(
                        org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria
                                .where("projectId").`is`(targetId.toString())
                                .and("key").`is`(prefKey),
                        ), "agent_preferences",
                    ).awaitSingle()
                }
            }
        }

        // Bulk migrate all remaining data
        val allCollections = listOf(
            "tasks", "meetings", "environments", "guidelines", "agent_preferences",
            "filtering_rules", "agent_learning", "llm_costs", "error_logs",
            "user_corrections", "approval_queue", "merge_requests", "git_commits",
            "teams_message_index", "slack_message_index", "discord_message_index",
            "email_message_index", "bugtracker_issues", "wiki_pages",
        )

        var totalMigrated = 0L
        for (collName in allCollections) {
            try {
                val query = org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("projectId").`is`(sourceId.toString()),
                )
                val update = org.springframework.data.mongodb.core.query.Update()
                    .set("projectId", targetId.toString())
                val result = mongoTemplate.updateMulti(query, update, collName).awaitSingle()
                if (result.modifiedCount > 0) {
                    logger.info { "MERGE: $collName — migrated ${result.modifiedCount} documents" }
                    totalMigrated += result.modifiedCount
                }
            } catch (e: Exception) {
                logger.warn(e) { "MERGE: $collName — partial migration: ${e.message}" }
            }
        }

        // Retag KB entries
        try {
            knowledgeClient.retagProjectId(sourceId.toString(), targetId.toString())
        } catch (e: Exception) {
            logger.warn(e) { "MERGE: KB retag failed (non-fatal)" }
        }

        // Delete source project
        projectRepository.delete(source)

        logger.info { "MERGE_DONE: ${source.name} → ${target.name} — $totalMigrated documents migrated" }
    }

    suspend fun getProjectByName(name: String?): ProjectDocument =
        requireNotNull(name?.let { n -> projectRepository.findAll().toList().find { it.name == n } }) {
            "Project not found with name: $name"
        }

    suspend fun getProjectById(projectId: ProjectId): ProjectDocument =
        requireNotNull(getProjectByIdOrNull(projectId)) {
            "Project not found with id: $projectId"
        }

    suspend fun getProjectByIdOrNull(projectId: ProjectId): ProjectDocument? =
        projectRepository.getById(projectId)

    /**
     * Get the JERVIS internal project. Prefers SystemConfig setting, falls back to per-client auto-create.
     */
    suspend fun getOrCreateJervisProject(clientId: ClientId): ProjectDocument {
        // 1. Try SystemConfig (explicit selection from Settings UI)
        val config = systemConfigRepository.findById(SystemConfigDocument.SINGLETON_ID)
        if (config?.jervisInternalProjectId != null) {
            val configured = projectRepository.getById(ProjectId(config.jervisInternalProjectId))
            if (configured != null) return configured
        }

        // 2. Fallback: find or create per-client internal project
        val existing = projectRepository.findByClientIdAndIsJervisInternal(clientId, true)
        if (existing != null) return existing

        val project = ProjectDocument(
            clientId = clientId,
            name = "JERVIS Internal",
            description = "Interní projekt pro plánování a orchestraci",
            isJervisInternal = true,
        )
        val saved = projectRepository.save(project)
        directoryStructureService.ensureProjectDirectories(saved.clientId, saved.id)
        logger.info { "Auto-created JERVIS internal project for client $clientId" }
        return saved
    }
}
