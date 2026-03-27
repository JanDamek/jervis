package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.environment.ComponentState
import com.jervis.environment.ComponentType
import com.jervis.environment.EnvironmentComponent
import com.jervis.environment.EnvironmentDocument
import com.jervis.environment.EnvironmentState
import com.jervis.environment.EnvironmentTier
import com.jervis.environment.PropertyMapping
import com.jervis.environment.toDto
import com.jervis.environment.COMPONENT_DEFAULTS
import com.jervis.environment.EnvironmentK8sService
import com.jervis.environment.EnvironmentService
import com.jervis.environment.PROPERTY_MAPPING_TEMPLATES
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId

private val logger = KotlinLogging.logger {}

private val internalJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Internal REST endpoints for environment CRUD operations.
 *
 * Called by the MCP server (Python) for chat/agent environment management tools.
 * Phase 4: Chat-first environment management — agents call these to create,
 * configure, and deploy environments via natural language.
 */
fun Routing.installInternalEnvironmentApi(
    environmentService: EnvironmentService,
    environmentK8sService: EnvironmentK8sService,
) {
    // --- List environments (optionally filtered by clientId) ---
    get("/internal/environments") {
        try {
            val clientId = call.request.queryParameters["clientId"]
            val envs = if (clientId != null) {
                environmentService.listEnvironmentsForClient(ClientId(ObjectId(clientId)))
            } else {
                environmentService.getAllEnvironments()
            }
            val dtos = envs.map { it.toDto() }
            val json = internalJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    com.jervis.dto.environment.EnvironmentDto.serializer(),
                ),
                dtos,
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to list environments" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Get single environment ---
    get("/internal/environments/{id}") {
        try {
            val id = call.parameters["id"] ?: return@get call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                env.toDto(),
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: IllegalArgumentException) {
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get environment" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Create environment ---
    post("/internal/environments") {
        try {
            val body = call.receive<CreateEnvironmentRequest>()
            val tier = body.tier?.let { EnvironmentTier.valueOf(it.uppercase()) } ?: EnvironmentTier.DEV
            val env = EnvironmentDocument(
                clientId = ClientId(ObjectId(body.clientId)),
                name = body.name,
                namespace = body.namespace ?: body.name.lowercase().replace(Regex("[^a-z0-9-]"), "-"),
                tier = tier,
                description = body.description,
                agentInstructions = body.agentInstructions,
                storageSizeGi = body.storageSizeGi ?: 5,
            )
            val saved = environmentService.saveEnvironment(env)
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                saved.toDto(),
            )
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.Created)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to create environment" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Delete environment ---
    delete("/internal/environments/{id}") {
        try {
            val id = call.parameters["id"] ?: return@delete call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            // Deprovision first if running
            val env = environmentService.getEnvironmentByIdOrNull(EnvironmentId(ObjectId(id)))
            if (env != null && env.state == EnvironmentState.RUNNING) {
                try {
                    environmentK8sService.deprovisionEnvironment(env.id, deleteNamespace = true)
                } catch (deprovisionError: Exception) {
                    logger.warn(deprovisionError) { "Failed to deprovision before delete, continuing with delete" }
                }
            }
            environmentService.deleteEnvironment(EnvironmentId(ObjectId(id)))
            call.respondText("{\"ok\":true}", ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete environment" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Add component to environment ---
    post("/internal/environments/{id}/components") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val body = call.receive<AddComponentRequest>()
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))

            val componentType = ComponentType.valueOf(body.type.uppercase())
            val defaults = COMPONENT_DEFAULTS[componentType]

            // Resolve image: explicit > version match > default
            val image = body.image ?: if (body.version != null && defaults != null) {
                defaults.versions.find { it.label.contains(body.version, ignoreCase = true) }?.image
                    ?: defaults.image
            } else {
                defaults?.image
            }

            val ports = defaults?.ports ?: emptyList()
            val envVars = (defaults?.defaultEnvVars ?: emptyMap()) + (body.envVars ?: emptyMap())
            val volumeMountPath = defaults?.defaultVolumeMountPath

            val componentId = body.name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
            val component = EnvironmentComponent(
                id = componentId,
                name = body.name,
                type = componentType,
                image = image,
                ports = ports,
                envVars = envVars,
                volumeMountPath = volumeMountPath,
                sourceRepo = body.sourceRepo,
                sourceBranch = body.sourceBranch,
                dockerfilePath = body.dockerfilePath,
                startOrder = body.startOrder ?: (env.components.size * 10),
            )

            val updated = env.copy(components = env.components + component)
            val saved = environmentService.saveEnvironment(updated)
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                saved.toDto(),
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to add component" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Configure component (update env vars, image, ports, resources) ---
    put("/internal/environments/{id}/components/{componentName}") {
        try {
            val id = call.parameters["id"] ?: return@put call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val componentName = call.parameters["componentName"] ?: return@put call.respondText(
                "{\"error\":\"Missing componentName\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val body = call.receive<ConfigureComponentRequest>()
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))

            val componentIndex = env.components.indexOfFirst { it.name == componentName || it.id == componentName }
            if (componentIndex == -1) {
                return@put call.respondText(
                    "{\"error\":\"Component '$componentName' not found\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            }

            val existing = env.components[componentIndex]
            val updated = existing.copy(
                image = body.image ?: existing.image,
                envVars = if (body.envVars != null) existing.envVars + body.envVars else existing.envVars,
                cpuLimit = body.cpuLimit ?: existing.cpuLimit,
                memoryLimit = body.memoryLimit ?: existing.memoryLimit,
                sourceRepo = body.sourceRepo ?: existing.sourceRepo,
                sourceBranch = body.sourceBranch ?: existing.sourceBranch,
                dockerfilePath = body.dockerfilePath ?: existing.dockerfilePath,
            )

            val updatedComponents = env.components.toMutableList()
            updatedComponents[componentIndex] = updated
            val saved = environmentService.saveEnvironment(env.copy(components = updatedComponents))
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                saved.toDto(),
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to configure component" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Deploy / provision environment ---
    post("/internal/environments/{id}/deploy") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val env = environmentK8sService.provisionEnvironment(EnvironmentId(ObjectId(id)))
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                env.toDto(),
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to deploy environment" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Stop / deprovision environment ---
    post("/internal/environments/{id}/stop") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val env = environmentK8sService.deprovisionEnvironment(EnvironmentId(ObjectId(id)))
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                env.toDto(),
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to stop environment" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Sync environment resources (re-apply from DB) ---
    post("/internal/environments/{id}/sync") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val env = environmentK8sService.syncEnvironmentResources(EnvironmentId(ObjectId(id)))
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                env.toDto(),
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to sync environment" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Get environment status ---
    get("/internal/environments/{id}/status") {
        try {
            val id = call.parameters["id"] ?: return@get call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val status = environmentK8sService.getEnvironmentStatus(EnvironmentId(ObjectId(id)))
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentStatusDto.serializer(),
                status,
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get environment status" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Clone environment ---
    post("/internal/environments/{id}/clone") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val body = call.receive<CloneEnvironmentRequest>()
            val newNamespace = body.newNamespace
                ?: body.newName.lowercase().replace(Regex("[^a-z0-9-]"), "-")
            val newTier = body.newTier?.let { EnvironmentTier.valueOf(it.uppercase()) }

            val cloned = environmentService.cloneEnvironment(
                sourceId = EnvironmentId(ObjectId(id)),
                newName = body.newName,
                newNamespace = newNamespace,
                targetClientId = body.targetClientId?.let { ClientId(ObjectId(it)) },
                targetGroupId = body.targetGroupId?.let { ProjectGroupId(ObjectId(it)) },
                targetProjectId = body.targetProjectId?.let { ProjectId(ObjectId(it)) },
                newTier = newTier,
            )
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                cloned.toDto(),
            )
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.Created)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to clone environment" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Add property mapping ---
    post("/internal/environments/{id}/property-mappings") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val body = call.receive<AddPropertyMappingRequest>()
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))

            // Validate components exist
            val projectComp = env.components.find { it.id == body.projectComponentId }
                ?: return@post call.respondText(
                    "{\"error\":\"Project component '${body.projectComponentId}' not found\"}",
                    ContentType.Application.Json, HttpStatusCode.NotFound,
                )
            val targetComp = env.components.find { it.id == body.targetComponentId }
                ?: return@post call.respondText(
                    "{\"error\":\"Target component '${body.targetComponentId}' not found\"}",
                    ContentType.Application.Json, HttpStatusCode.NotFound,
                )

            // Check for duplicate
            val exists = env.propertyMappings.any {
                it.projectComponentId == body.projectComponentId && it.propertyName == body.propertyName
            }
            if (exists) {
                return@post call.respondText(
                    "{\"error\":\"Mapping for '${body.propertyName}' on '${projectComp.name}' already exists\"}",
                    ContentType.Application.Json, HttpStatusCode.Conflict,
                )
            }

            val mapping = PropertyMapping(
                projectComponentId = body.projectComponentId,
                propertyName = body.propertyName,
                targetComponentId = body.targetComponentId,
                valueTemplate = body.valueTemplate,
            )
            val updated = env.copy(propertyMappings = env.propertyMappings + mapping)
            val saved = environmentService.saveEnvironment(updated)
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                saved.toDto(),
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to add property mapping" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Auto-suggest property mappings ---
    post("/internal/environments/{id}/property-mappings/auto-suggest") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))

            val projects = env.components.filter { it.type == ComponentType.PROJECT }
            val infra = env.components.filter { it.type != ComponentType.PROJECT }

            var newMappings = env.propertyMappings.toList()
            var addedCount = 0

            for (project in projects) {
                for (infraComp in infra) {
                    val templates = PROPERTY_MAPPING_TEMPLATES[infraComp.type] ?: continue
                    for (tmpl in templates) {
                        // Skip if already exists
                        val exists = newMappings.any {
                            it.projectComponentId == project.id && it.propertyName == tmpl.envVarName
                        }
                        if (exists) continue

                        newMappings = newMappings + PropertyMapping(
                            projectComponentId = project.id,
                            propertyName = tmpl.envVarName,
                            targetComponentId = infraComp.id,
                            valueTemplate = tmpl.valueTemplate,
                        )
                        addedCount++
                    }
                }
            }

            val saved = environmentService.saveEnvironment(env.copy(propertyMappings = newMappings))
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                saved.toDto(),
            )
            // Return with count header
            call.response.headers.append("X-Mappings-Added", addedCount.toString())
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to auto-suggest property mappings" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Discover K8s namespace → create environment ---
    post("/internal/environments/discover") {
        try {
            val body = call.receive<DiscoverNamespaceRequest>()
            val tier = body.tier?.let { EnvironmentTier.valueOf(it.uppercase()) } ?: EnvironmentTier.DEV
            val env = environmentK8sService.discoverFromNamespace(
                namespace = body.namespace,
                clientId = ClientId(ObjectId(body.clientId)),
                name = body.name,
                tier = tier,
            )
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                env.toDto(),
            )
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.Created)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to discover namespace" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Replicate environment (clone + deploy in one step) ---
    post("/internal/environments/{id}/replicate") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val body = call.receive<ReplicateEnvironmentRequest>()
            val newNamespace = body.newNamespace
                ?: body.newName.lowercase().replace(Regex("[^a-z0-9-]"), "-")
            val newTier = body.newTier?.let { EnvironmentTier.valueOf(it.uppercase()) }

            // 1. Clone
            val cloned = environmentService.cloneEnvironment(
                sourceId = EnvironmentId(ObjectId(id)),
                newName = body.newName,
                newNamespace = newNamespace,
                targetClientId = body.targetClientId?.let { ClientId(ObjectId(it)) },
                newTier = newTier,
            )

            // 2. Deploy
            val deployed = environmentK8sService.provisionEnvironment(cloned.id)

            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                deployed.toDto(),
            )
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.Created)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to replicate environment" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Sync from K8s (K8s is source of truth) ---
    post("/internal/environments/{id}/sync-from-k8s") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val env = environmentK8sService.syncFromK8s(EnvironmentId(ObjectId(id)))
            val json = internalJson.encodeToString(
                com.jervis.dto.environment.EnvironmentDto.serializer(),
                env.toDto(),
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to sync from K8s" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Get component templates ---
    get("/internal/environments/templates") {
        try {
            val templates = COMPONENT_DEFAULTS.map { (type, defaults) ->
                mapOf(
                    "type" to type.name,
                    "versions" to defaults.versions.map { mapOf("label" to it.label, "image" to it.image) },
                    "defaultPorts" to defaults.ports.map { mapOf("containerPort" to it.containerPort, "servicePort" to it.servicePort, "name" to it.name) },
                    "defaultEnvVars" to defaults.defaultEnvVars,
                    "defaultVolumeMountPath" to defaults.defaultVolumeMountPath,
                )
            }
            val jsonTemplates = templates.map { template ->
                kotlinx.serialization.json.JsonObject(
                    template.mapValues { (_, v) -> toJsonElement(v) }
                )
            }
            val json = internalJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    kotlinx.serialization.json.JsonElement.serializer(),
                ),
                jsonTemplates,
            )
            call.respondText(json, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get templates" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

// --- JSON element helper (reuse from KtorRpcServer pattern) ---
private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement {
    return when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is Map<*, *> -> kotlinx.serialization.json.buildJsonObject {
            value.forEach { (k, v) -> put(k.toString(), toJsonElement(v)) }
        }
        is List<*> -> kotlinx.serialization.json.buildJsonArray {
            value.forEach { add(toJsonElement(it)) }
        }
        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
    }
}

// --- Request DTOs ---

@Serializable
data class CreateEnvironmentRequest(
    val clientId: String,
    val name: String,
    val namespace: String? = null,
    val tier: String? = null,
    val description: String? = null,
    val agentInstructions: String? = null,
    val storageSizeGi: Int? = null,
)

@Serializable
data class CloneEnvironmentRequest(
    val newName: String,
    val newNamespace: String? = null,
    val newTier: String? = null,
    val targetClientId: String? = null,
    val targetGroupId: String? = null,
    val targetProjectId: String? = null,
)

@Serializable
data class AddComponentRequest(
    val name: String,
    val type: String,
    val image: String? = null,
    val version: String? = null,
    val envVars: Map<String, String>? = null,
    val sourceRepo: String? = null,
    val sourceBranch: String? = null,
    val dockerfilePath: String? = null,
    val startOrder: Int? = null,
)

@Serializable
data class AddPropertyMappingRequest(
    val projectComponentId: String,
    val propertyName: String,
    val targetComponentId: String,
    val valueTemplate: String,
)

@Serializable
data class DiscoverNamespaceRequest(
    val namespace: String,
    val clientId: String,
    val name: String? = null,
    val tier: String? = null,
)

@Serializable
data class ReplicateEnvironmentRequest(
    val newName: String,
    val newNamespace: String? = null,
    val newTier: String? = null,
    val targetClientId: String? = null,
)

@Serializable
data class ConfigureComponentRequest(
    val image: String? = null,
    val envVars: Map<String, String>? = null,
    val cpuLimit: String? = null,
    val memoryLimit: String? = null,
    val sourceRepo: String? = null,
    val sourceBranch: String? = null,
    val dockerfilePath: String? = null,
)
