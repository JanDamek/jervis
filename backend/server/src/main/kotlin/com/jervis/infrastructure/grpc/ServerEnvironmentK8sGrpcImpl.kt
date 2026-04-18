package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.GetDeploymentStatusRequest
import com.jervis.contracts.server.GetDeploymentStatusResponse
import com.jervis.contracts.server.GetNamespaceStatusRequest
import com.jervis.contracts.server.GetNamespaceStatusResponse
import com.jervis.contracts.server.GetPodLogsRequest
import com.jervis.contracts.server.GetPodLogsResponse
import com.jervis.contracts.server.K8sCondition
import com.jervis.contracts.server.K8sDeployment
import com.jervis.contracts.server.K8sDeploymentDetail
import com.jervis.contracts.server.K8sEvent
import com.jervis.contracts.server.K8sNamespaceStatus
import com.jervis.contracts.server.K8sPod
import com.jervis.contracts.server.K8sResourceList
import com.jervis.contracts.server.K8sService
import com.jervis.contracts.server.ListNamespaceResourcesRequest
import com.jervis.contracts.server.ListNamespaceResourcesResponse
import com.jervis.contracts.server.RestartDeploymentRequest
import com.jervis.contracts.server.RestartDeploymentResponse
import com.jervis.contracts.server.ScaleDeploymentRequest
import com.jervis.contracts.server.ScaleDeploymentResponse
import com.jervis.contracts.server.ServerEnvironmentK8sServiceGrpcKt
import com.jervis.environment.EnvironmentResourceService
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
                .setData(resources.toResourceListProto())
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
            .setData(details.toDeploymentDetailProto())
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
            .setData(status.toNamespaceStatusProto())
            .build()
    } catch (e: IllegalStateException) {
        GetNamespaceStatusResponse.newBuilder().setOk(false)
            .setError(e.message.orEmpty()).build()
    } catch (e: Exception) {
        logger.warn(e) { "Failed to get namespace status for ${request.namespace}" }
        GetNamespaceStatusResponse.newBuilder().setOk(false)
            .setError(e.message.orEmpty()).build()
    }
}

// ── Map → proto converters ─────────────────────────────────────────────

private fun Map<String, List<Map<String, Any?>>>.toResourceListProto(): K8sResourceList {
    val builder = K8sResourceList.newBuilder()
    this["pods"]?.forEach { builder.addPods(it.toPodProto()) }
    this["deployments"]?.forEach { builder.addDeployments(it.toDeploymentProto()) }
    this["services"]?.forEach { builder.addServices(it.toServiceProto()) }
    return builder.build()
}

private fun Map<String, Any?>.toPodProto(): K8sPod =
    K8sPod.newBuilder()
        .setName(str("name"))
        .setPhase(str("phase"))
        .setReady(bool("ready"))
        .setRestartCount(int("restartCount"))
        .setCreatedAt(str("createdAt"))
        .build()

private fun Map<String, Any?>.toDeploymentProto(): K8sDeployment =
    K8sDeployment.newBuilder()
        .setName(str("name"))
        .setReplicas(int("replicas"))
        .setAvailableReplicas(int("availableReplicas"))
        .setReady(bool("ready"))
        .setImage(str("image"))
        .setCreatedAt(str("createdAt"))
        .build()

private fun Map<String, Any?>.toServiceProto(): K8sService =
    K8sService.newBuilder()
        .setName(str("name"))
        .setType(str("type"))
        .setClusterIp(str("clusterIP"))
        .addAllPorts(strList("ports"))
        .setCreatedAt(str("createdAt"))
        .build()

private fun Map<String, Any?>.toDeploymentDetailProto(): K8sDeploymentDetail {
    val builder = K8sDeploymentDetail.newBuilder()
        .setName(str("name"))
        .setNamespace(str("namespace"))
        .setReplicas(int("replicas"))
        .setAvailableReplicas(int("availableReplicas"))
        .setReady(bool("ready"))
        .setImage(str("image"))
        .setCreatedAt(str("createdAt"))
    @Suppress("UNCHECKED_CAST")
    (this["conditions"] as? List<Map<String, Any?>>)?.forEach {
        builder.addConditions(
            K8sCondition.newBuilder()
                .setType(it.str("type"))
                .setStatus(it.str("status"))
                .setReason(it.str("reason"))
                .setMessage(it.str("message"))
                .setLastTransitionTime(it.str("lastTransitionTime"))
                .build(),
        )
    }
    @Suppress("UNCHECKED_CAST")
    (this["events"] as? List<Map<String, Any?>>)?.forEach {
        builder.addEvents(
            K8sEvent.newBuilder()
                .setType(it.str("type"))
                .setReason(it.str("reason"))
                .setMessage(it.str("message"))
                .setTime(it.str("time"))
                .build(),
        )
    }
    return builder.build()
}

private fun Map<String, Any?>.toNamespaceStatusProto(): K8sNamespaceStatus {
    @Suppress("UNCHECKED_CAST")
    val pods = this["pods"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val deployments = this["deployments"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val services = this["services"] as? Map<String, Any?> ?: emptyMap()
    return K8sNamespaceStatus.newBuilder()
        .setNamespace(str("namespace"))
        .setHealthy(bool("healthy"))
        .setTotalPods(pods.int("total"))
        .setRunningPods(pods.int("running"))
        .addAllCrashingPods(pods.strList("crashing"))
        .setTotalDeployments(deployments.int("total"))
        .setReadyDeployments(deployments.int("ready"))
        .setTotalServices(services.int("total"))
        .build()
}

private fun Map<String, Any?>.str(key: String): String = when (val v = this[key]) {
    null -> ""
    is String -> v
    else -> v.toString()
}

private fun Map<String, Any?>.int(key: String): Int = when (val v = this[key]) {
    null -> 0
    is Number -> v.toInt()
    is String -> v.toIntOrNull() ?: 0
    else -> 0
}

private fun Map<String, Any?>.bool(key: String): Boolean = when (val v = this[key]) {
    null -> false
    is Boolean -> v
    is String -> v.equals("true", ignoreCase = true)
    else -> false
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.strList(key: String): List<String> =
    (this[key] as? List<*>)?.map { it?.toString() ?: "" } ?: emptyList()
