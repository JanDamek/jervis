package com.jervis.rpc

import com.jervis.common.types.EnvironmentId
import com.jervis.dto.environment.*
import com.jervis.service.IEnvironmentResourceService
import com.jervis.service.environment.EnvironmentResourceService
import com.jervis.service.environment.EnvironmentService
import com.jervis.service.error.ErrorLogService
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class EnvironmentResourceRpcImpl(
    private val environmentService: EnvironmentService,
    private val environmentResourceService: EnvironmentResourceService,
    errorLogService: ErrorLogService,
) : BaseRpcImpl(errorLogService),
    IEnvironmentResourceService {

    private suspend fun resolveNamespace(environmentId: String): String {
        val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(environmentId)))
        return env.namespace
    }

    override suspend fun listResources(environmentId: String): K8sResourceListDto =
        executeWithErrorHandling("listResources") {
            val namespace = resolveNamespace(environmentId)
            val raw = environmentResourceService.listResources(namespace)

            K8sResourceListDto(
                pods = (raw["pods"] ?: emptyList()).map { r ->
                    K8sPodDto(
                        name = r["name"] as? String ?: "",
                        phase = r["phase"] as? String,
                        ready = r["ready"] as? Boolean ?: false,
                        restartCount = (r["restartCount"] as? Number)?.toInt() ?: 0,
                        createdAt = r["createdAt"]?.toString(),
                    )
                },
                deployments = (raw["deployments"] ?: emptyList()).map { r ->
                    K8sDeploymentDto(
                        name = r["name"] as? String ?: "",
                        replicas = (r["replicas"] as? Number)?.toInt() ?: 0,
                        availableReplicas = (r["availableReplicas"] as? Number)?.toInt() ?: 0,
                        ready = r["ready"] as? Boolean ?: false,
                        image = r["image"] as? String,
                        createdAt = r["createdAt"]?.toString(),
                    )
                },
                services = (raw["services"] ?: emptyList()).map { r ->
                    K8sServiceDto(
                        name = r["name"] as? String ?: "",
                        type = r["type"] as? String,
                        clusterIP = r["clusterIP"] as? String,
                        ports = (r["ports"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        createdAt = r["createdAt"]?.toString(),
                    )
                },
            )
        }

    override suspend fun getPodLogs(environmentId: String, podName: String, tailLines: Int): String =
        executeWithErrorHandling("getPodLogs") {
            val namespace = resolveNamespace(environmentId)
            environmentResourceService.getPodLogs(namespace, podName, tailLines)
        }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getDeploymentDetails(environmentId: String, deploymentName: String): K8sDeploymentDetailDto =
        executeWithErrorHandling("getDeploymentDetails") {
            val namespace = resolveNamespace(environmentId)
            val raw = environmentResourceService.getDeploymentDetails(namespace, deploymentName)

            val conditions = (raw["conditions"] as? List<Map<String, Any?>>)?.map { c ->
                K8sConditionDto(
                    type = c["type"] as? String ?: "",
                    status = c["status"] as? String ?: "",
                    reason = c["reason"] as? String,
                    message = c["message"] as? String,
                    lastTransitionTime = c["lastTransitionTime"]?.toString(),
                )
            } ?: emptyList()

            val events = (raw["events"] as? List<Map<String, Any?>>)?.map { e ->
                K8sEventDto(
                    type = e["type"] as? String,
                    reason = e["reason"] as? String,
                    message = e["message"] as? String,
                    time = e["time"]?.toString(),
                )
            } ?: emptyList()

            K8sDeploymentDetailDto(
                name = raw["name"] as? String ?: deploymentName,
                namespace = raw["namespace"] as? String ?: namespace,
                replicas = (raw["replicas"] as? Number)?.toInt() ?: 0,
                availableReplicas = (raw["availableReplicas"] as? Number)?.toInt() ?: 0,
                ready = raw["ready"] as? Boolean ?: false,
                image = raw["image"] as? String,
                createdAt = raw["createdAt"]?.toString(),
                conditions = conditions,
                events = events,
            )
        }

    override suspend fun scaleDeployment(environmentId: String, deploymentName: String, replicas: Int) =
        executeWithErrorHandling("scaleDeployment") {
            val namespace = resolveNamespace(environmentId)
            environmentResourceService.scaleDeployment(namespace, deploymentName, replicas)
        }

    override suspend fun restartDeployment(environmentId: String, deploymentName: String) =
        executeWithErrorHandling("restartDeployment") {
            val namespace = resolveNamespace(environmentId)
            environmentResourceService.restartDeployment(namespace, deploymentName)
        }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getNamespaceStatus(environmentId: String): K8sNamespaceStatusDto =
        executeWithErrorHandling("getNamespaceStatus") {
            val namespace = resolveNamespace(environmentId)
            val raw = environmentResourceService.getNamespaceStatus(namespace)

            val pods = raw["pods"] as? Map<String, Any?> ?: emptyMap()
            val deployments = raw["deployments"] as? Map<String, Any?> ?: emptyMap()
            val services = raw["services"] as? Map<String, Any?> ?: emptyMap()

            K8sNamespaceStatusDto(
                namespace = raw["namespace"] as? String ?: namespace,
                healthy = raw["healthy"] as? Boolean ?: false,
                totalPods = (pods["total"] as? Number)?.toInt() ?: 0,
                runningPods = (pods["running"] as? Number)?.toInt() ?: 0,
                crashingPods = (pods["crashing"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                totalDeployments = (deployments["total"] as? Number)?.toInt() ?: 0,
                readyDeployments = (deployments["ready"] as? Number)?.toInt() ?: 0,
                totalServices = (services["total"] as? Number)?.toInt() ?: 0,
            )
        }
}
