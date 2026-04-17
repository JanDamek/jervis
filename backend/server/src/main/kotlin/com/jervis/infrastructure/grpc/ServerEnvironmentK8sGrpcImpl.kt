package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.GetDeploymentStatusRequest
import com.jervis.contracts.server.GetDeploymentStatusResponse
import com.jervis.contracts.server.GetNamespaceStatusRequest
import com.jervis.contracts.server.GetNamespaceStatusResponse
import com.jervis.contracts.server.GetPodLogsRequest
import com.jervis.contracts.server.GetPodLogsResponse
import com.jervis.contracts.server.ListNamespaceResourcesRequest
import com.jervis.contracts.server.ListNamespaceResourcesResponse
import com.jervis.contracts.server.RestartDeploymentRequest
import com.jervis.contracts.server.RestartDeploymentResponse
import com.jervis.contracts.server.ScaleDeploymentRequest
import com.jervis.contracts.server.ScaleDeploymentResponse
import com.jervis.contracts.server.ServerEnvironmentK8sServiceGrpcKt
import com.jervis.environment.EnvironmentResourceService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerEnvironmentK8sGrpcImpl(
    private val environmentResourceService: EnvironmentResourceService,
) : ServerEnvironmentK8sServiceGrpcKt.ServerEnvironmentK8sServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun listNamespaceResources(
        request: ListNamespaceResourcesRequest,
    ): ListNamespaceResourcesResponse {
        val type = request.type.takeIf { it.isNotBlank() } ?: "all"
        return try {
            val resources = environmentResourceService.listResources(request.namespace, type)
            ListNamespaceResourcesResponse.newBuilder()
                .setOk(true)
                .setDataJson(anyToJson(resources).toString())
                .build()
        } catch (e: IllegalStateException) {
            ListNamespaceResourcesResponse.newBuilder().setOk(false)
                .setError(e.message.orEmpty()).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to list resources in ${request.namespace}" }
            ListNamespaceResourcesResponse.newBuilder().setOk(false)
                .setError(e.message.orEmpty()).build()
        }
    }

    override suspend fun getPodLogs(request: GetPodLogsRequest): GetPodLogsResponse {
        val tail = if (request.tailLines > 0) request.tailLines else 100
        return try {
            val logs = environmentResourceService.getPodLogs(request.namespace, request.podName, tail)
            GetPodLogsResponse.newBuilder().setOk(true).setLogs(logs).build()
        } catch (e: IllegalStateException) {
            GetPodLogsResponse.newBuilder().setOk(false)
                .setError("Access denied: ${e.message}").build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get pod logs for ${request.podName} in ${request.namespace}" }
            GetPodLogsResponse.newBuilder().setOk(false)
                .setError(e.message.orEmpty()).build()
        }
    }

    override suspend fun getDeploymentStatus(
        request: GetDeploymentStatusRequest,
    ): GetDeploymentStatusResponse = try {
        val details = environmentResourceService.getDeploymentDetails(
            request.namespace, request.deploymentName,
        )
        GetDeploymentStatusResponse.newBuilder()
            .setOk(true)
            .setDataJson(anyToJson(details).toString())
            .build()
    } catch (e: Exception) {
        logger.warn(e) { "Failed to get deployment ${request.deploymentName} in ${request.namespace}" }
        GetDeploymentStatusResponse.newBuilder().setOk(false)
            .setError(e.message.orEmpty()).build()
    }

    override suspend fun scaleDeployment(
        request: ScaleDeploymentRequest,
    ): ScaleDeploymentResponse = try {
        environmentResourceService.scaleDeployment(
            request.namespace, request.deploymentName, request.replicas,
        )
        ScaleDeploymentResponse.newBuilder()
            .setOk(true)
            .setMessage("Scaled ${request.deploymentName} to ${request.replicas} replicas")
            .build()
    } catch (e: Exception) {
        logger.warn(e) { "Failed to scale ${request.deploymentName} in ${request.namespace}" }
        ScaleDeploymentResponse.newBuilder().setOk(false)
            .setError(e.message.orEmpty()).build()
    }

    override suspend fun restartDeployment(
        request: RestartDeploymentRequest,
    ): RestartDeploymentResponse = try {
        environmentResourceService.restartDeployment(request.namespace, request.deploymentName)
        RestartDeploymentResponse.newBuilder()
            .setOk(true)
            .setMessage("Restart triggered for ${request.deploymentName}")
            .build()
    } catch (e: Exception) {
        logger.warn(e) { "Failed to restart ${request.deploymentName} in ${request.namespace}" }
        RestartDeploymentResponse.newBuilder().setOk(false)
            .setError(e.message.orEmpty()).build()
    }

    override suspend fun getNamespaceStatus(
        request: GetNamespaceStatusRequest,
    ): GetNamespaceStatusResponse = try {
        val status = environmentResourceService.getNamespaceStatus(request.namespace)
        GetNamespaceStatusResponse.newBuilder()
            .setOk(true)
            .setDataJson(anyToJson(status).toString())
            .build()
    } catch (e: IllegalStateException) {
        GetNamespaceStatusResponse.newBuilder().setOk(false)
            .setError(e.message.orEmpty()).build()
    } catch (e: Exception) {
        logger.warn(e) { "Failed to get namespace status for ${request.namespace}" }
        GetNamespaceStatusResponse.newBuilder().setOk(false)
            .setError(e.message.orEmpty()).build()
    }

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) -> put(k.toString(), anyToJson(v)) }
        }
        is List<*> -> buildJsonArray { value.forEach { add(anyToJson(it)) } }
        else -> JsonPrimitive(value.toString())
    }
}
