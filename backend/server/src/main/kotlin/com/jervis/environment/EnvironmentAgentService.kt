package com.jervis.environment

import com.jervis.dto.environment.DeploymentValidationResult
import com.jervis.dto.environment.EnvironmentAgentJobType
import com.jervis.dto.environment.EnvironmentAgentRequest
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * EPIC 17: Environment Agent Enhancement.
 *
 * S1: On-demand environment agent dispatch (K8s job context).
 * S2: Deployment validation (health + log analysis).
 * S3: Debug assistance (kubectl logs/describe).
 */
@Service
class EnvironmentAgentService {
    private val logger = KotlinLogging.logger {}

    /**
     * E17-S1: Dispatch an on-demand environment agent request.
     * Routes to the appropriate handler based on job type.
     */
    suspend fun dispatch(request: EnvironmentAgentRequest): Map<String, Any> {
        logger.info { "EnvironmentAgent dispatch: type=${request.jobType}, ns=${request.namespace}" }
        return when (request.jobType) {
            EnvironmentAgentJobType.DEPLOYMENT_VALIDATION ->
                mapOf("validation" to validateDeployment(request.namespace, request.targetResource))
            EnvironmentAgentJobType.DEBUG_LOGS ->
                mapOf("logs" to fetchPodLogs(request.namespace, request.targetResource, request.parameters))
            EnvironmentAgentJobType.DEBUG_STATUS ->
                mapOf("status" to describePod(request.namespace, request.targetResource))
            EnvironmentAgentJobType.GENERAL ->
                mapOf("info" to getNamespaceOverview(request.namespace))
        }
    }

    /**
     * E17-S2: Validate deployment health in a namespace.
     */
    suspend fun validateDeployment(
        namespace: String,
        targetDeployment: String? = null,
    ): DeploymentValidationResult {
        return try {
            val k8s = buildK8sClient()
            k8s.use { client ->
                val pods = if (targetDeployment != null) {
                    client.pods().inNamespace(namespace)
                        .withLabel("app", targetDeployment)
                        .list().items
                } else {
                    client.pods().inNamespace(namespace).list().items
                }

                val podCount = pods.size
                val readyCount = pods.count { pod ->
                    pod.status?.containerStatuses?.all { it.ready == true } == true
                }
                val errorPods = pods.filter { pod ->
                    pod.status?.phase in listOf("Failed", "CrashLoopBackOff", "Error") ||
                        pod.status?.containerStatuses?.any { it.ready != true } == true
                }.map { it.metadata?.name ?: "unknown" }

                // Collect recent error logs from unhealthy pods
                val logErrors = errorPods.take(3).flatMap { podName ->
                    try {
                        val logs = client.pods().inNamespace(namespace)
                            .withName(podName)
                            .tailingLines(50)
                            .log
                        extractErrorLines(logs)
                    } catch (e: Exception) {
                        listOf("Failed to fetch logs for $podName: ${e.message}")
                    }
                }

                // Collect recent events
                val events = client.v1().events().inNamespace(namespace)
                    .list().items
                    .filter { it.type == "Warning" }
                    .takeLast(10)
                    .map { "${it.reason}: ${it.message}" }

                DeploymentValidationResult(
                    healthy = errorPods.isEmpty() && podCount > 0,
                    podCount = podCount,
                    readyCount = readyCount,
                    errorPods = errorPods,
                    logErrors = logErrors,
                    events = events,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Deployment validation failed for $namespace" }
            DeploymentValidationResult(
                healthy = false,
                logErrors = listOf("Validation failed: ${e.message}"),
            )
        }
    }

    /**
     * E17-S3: Fetch pod logs for debugging.
     */
    suspend fun fetchPodLogs(
        namespace: String,
        podName: String?,
        parameters: Map<String, String> = emptyMap(),
    ): String {
        if (podName.isNullOrBlank()) return "Error: podName is required for DEBUG_LOGS"

        return try {
            val k8s = buildK8sClient()
            k8s.use { client ->
                val tailLines = parameters["tailLines"]?.toIntOrNull() ?: 100
                val container = parameters["container"]

                if (container != null) {
                    client.pods().inNamespace(namespace)
                        .withName(podName)
                        .inContainer(container)
                        .tailingLines(tailLines)
                        .log
                } else {
                    client.pods().inNamespace(namespace)
                        .withName(podName)
                        .tailingLines(tailLines)
                        .log
                }
            }
        } catch (e: Exception) {
            "Error fetching logs for $podName in $namespace: ${e.message}"
        }
    }

    /**
     * E17-S3: Describe a pod (status, conditions, events).
     */
    suspend fun describePod(
        namespace: String,
        podName: String?,
    ): Map<String, Any> {
        if (podName.isNullOrBlank()) return mapOf("error" to "podName is required for DEBUG_STATUS")

        return try {
            val k8s = buildK8sClient()
            k8s.use { client ->
                val pod = client.pods().inNamespace(namespace).withName(podName).get()
                    ?: return mapOf("error" to "Pod $podName not found in $namespace")

                val events = client.v1().events().inNamespace(namespace)
                    .list().items
                    .filter { it.involvedObject?.name == podName }
                    .takeLast(10)
                    .map { mapOf("type" to (it.type ?: ""), "reason" to (it.reason ?: ""), "message" to (it.message ?: "")) }

                mapOf(
                    "name" to (pod.metadata?.name ?: ""),
                    "phase" to (pod.status?.phase ?: "Unknown"),
                    "conditions" to (pod.status?.conditions?.map {
                        mapOf("type" to (it.type ?: ""), "status" to (it.status ?: ""), "reason" to (it.reason ?: ""))
                    } ?: emptyList<Map<String, String>>()),
                    "containers" to (pod.status?.containerStatuses?.map {
                        mapOf(
                            "name" to (it.name ?: ""),
                            "ready" to (it.ready?.toString() ?: "false"),
                            "restartCount" to (it.restartCount?.toString() ?: "0"),
                            "state" to (it.state?.toString() ?: "unknown"),
                        )
                    } ?: emptyList<Map<String, String>>()),
                    "events" to events,
                )
            }
        } catch (e: Exception) {
            mapOf("error" to "Failed to describe $podName: ${e.message}")
        }
    }

    private fun getNamespaceOverview(namespace: String): Map<String, Any> {
        return try {
            val k8s = buildK8sClient()
            k8s.use { client ->
                val pods = client.pods().inNamespace(namespace).list().items
                val deployments = client.apps().deployments().inNamespace(namespace).list().items
                val services = client.services().inNamespace(namespace).list().items

                mapOf(
                    "namespace" to namespace,
                    "podCount" to pods.size,
                    "deploymentCount" to deployments.size,
                    "serviceCount" to services.size,
                    "pods" to pods.map { "${it.metadata?.name}: ${it.status?.phase}" },
                    "deployments" to deployments.map { "${it.metadata?.name}: ${it.status?.readyReplicas ?: 0}/${it.spec?.replicas ?: 0}" },
                )
            }
        } catch (e: Exception) {
            mapOf("error" to "Failed to get overview: ${e.message}")
        }
    }

    private fun extractErrorLines(logs: String): List<String> =
        logs.lines()
            .filter { line ->
                val lower = line.lowercase()
                lower.contains("error") || lower.contains("exception") ||
                    lower.contains("fatal") || lower.contains("panic")
            }
            .takeLast(10)

    private fun buildK8sClient(): KubernetesClient = KubernetesClientBuilder().build()
}
