package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.entity.ComponentState
import com.jervis.entity.ComponentType
import com.jervis.entity.EnvironmentComponent
import com.jervis.entity.EnvironmentDocument
import com.jervis.entity.EnvironmentState
import com.jervis.mapper.toDto
import com.jervis.service.environment.COMPONENT_DEFAULTS
import com.jervis.service.environment.EnvironmentK8sService
import com.jervis.service.environment.EnvironmentService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
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
            val env = EnvironmentDocument(
                clientId = ClientId(ObjectId(body.clientId)),
                name = body.name,
                namespace = body.namespace ?: body.name.lowercase().replace(Regex("[^a-z0-9-]"), "-"),
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

    // --- Upload file to a running component pod ---
    post("/internal/environments/{id}/components/{componentName}/upload") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val componentName = call.parameters["componentName"] ?: return@post call.respondText(
                "{\"error\":\"Missing componentName\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))
            val component = env.components.find { it.name == componentName || it.id == componentName }
                ?: return@post call.respondText(
                    "{\"error\":\"Component '$componentName' not found\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            var fileName = "upload"
            var fileBytes: ByteArray? = null
            var targetDir = "/tmp"

            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        fileName = part.originalFileName ?: "upload"
                        fileBytes = part.provider().readBytes()
                    }
                    is PartData.FormItem -> {
                        when (part.name) {
                            "targetDir" -> targetDir = part.value
                            "fileName" -> fileName = part.value
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            val bytes = fileBytes ?: return@post call.respondText(
                "{\"error\":\"No file provided\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )

            // Max 100MB
            if (bytes.size > 100 * 1024 * 1024) {
                return@post call.respondText(
                    "{\"error\":\"File too large (max 100MB)\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }

            val targetPath = environmentK8sService.uploadFileToPod(
                namespace = env.namespace,
                componentName = component.name,
                fileBytes = bytes,
                fileName = fileName,
                targetDir = targetDir,
            )

            call.respondText(
                "{\"ok\":true,\"targetPath\":\"$targetPath\",\"sizeBytes\":${bytes.size}}",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to upload file to component" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Execute command in a running component pod ---
    post("/internal/environments/{id}/components/{componentName}/exec") {
        try {
            val id = call.parameters["id"] ?: return@post call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val componentName = call.parameters["componentName"] ?: return@post call.respondText(
                "{\"error\":\"Missing componentName\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val body = call.receive<ExecCommandRequest>()
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))
            val component = env.components.find { it.name == componentName || it.id == componentName }
                ?: return@post call.respondText(
                    "{\"error\":\"Component '$componentName' not found\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val output = environmentK8sService.execInPod(
                namespace = env.namespace,
                componentName = component.name,
                command = body.command,
            )

            call.respondText(
                internalJson.encodeToString(ExecCommandResponse.serializer(), ExecCommandResponse(output = output)),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to exec in component pod" }
            call.respondText(
                "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- List files in a component pod directory ---
    get("/internal/environments/{id}/components/{componentName}/files") {
        try {
            val id = call.parameters["id"] ?: return@get call.respondText(
                "{\"error\":\"Missing id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val componentName = call.parameters["componentName"] ?: return@get call.respondText(
                "{\"error\":\"Missing componentName\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val directory = call.request.queryParameters["dir"] ?: "/tmp"
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))
            val component = env.components.find { it.name == componentName || it.id == componentName }
                ?: return@get call.respondText(
                    "{\"error\":\"Component '$componentName' not found\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val listing = environmentK8sService.listFilesInPod(
                namespace = env.namespace,
                componentName = component.name,
                directory = directory,
            )

            call.respondText(
                "{\"directory\":\"$directory\",\"listing\":${internalJson.encodeToString(kotlinx.serialization.builtins.serializer<String>(), listing)}}",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to list files in component pod" }
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
            val json = internalJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    kotlinx.serialization.builtins.MapSerializer(
                        kotlinx.serialization.builtins.serializer<String>(),
                        kotlinx.serialization.json.JsonElement.serializer(),
                    ),
                ),
                templates.map { template ->
                    template.mapValues { (_, v) -> toJsonElement(v) }
                },
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
    val description: String? = null,
    val agentInstructions: String? = null,
    val storageSizeGi: Int? = null,
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
data class ConfigureComponentRequest(
    val image: String? = null,
    val envVars: Map<String, String>? = null,
    val cpuLimit: String? = null,
    val memoryLimit: String? = null,
    val sourceRepo: String? = null,
    val sourceBranch: String? = null,
    val dockerfilePath: String? = null,
)

@Serializable
data class ExecCommandRequest(
    val command: List<String>,
)

@Serializable
data class ExecCommandResponse(
    val output: String,
)
