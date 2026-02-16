package com.jervis.service.environment

import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * K8s resource inspection service for environment namespaces.
 *
 * Provides read/write operations on K8s resources within Jervis-managed namespaces.
 * Called by internal REST endpoints (MCP server + future UI).
 *
 * Security: Only operates on namespaces labeled `managed-by=jervis-server`.
 */
@Service
class EnvironmentResourceService {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val MAX_LOG_LINES = 1000
        private const val MANAGED_BY_LABEL = "managed-by"
        private const val MANAGED_BY_VALUE = "jervis-server"
    }

    private fun buildK8sClient(): KubernetesClient {
        val config = ConfigBuilder()
            .withRequestTimeout(60_000)
            .withConnectionTimeout(15_000)
            .build()
        return KubernetesClientBuilder()
            .withConfig(config)
            .build()
    }

    /**
     * Validate that namespace is managed by Jervis.
     * Throws IllegalStateException if not.
     */
    private fun validateNamespace(client: KubernetesClient, namespace: String) {
        val ns = client.namespaces().withName(namespace).get()
            ?: throw IllegalArgumentException("Namespace $namespace not found")
        if (ns.metadata?.labels?.get(MANAGED_BY_LABEL) != MANAGED_BY_VALUE) {
            throw IllegalStateException(
                "Namespace $namespace is not managed by Jervis, access denied"
            )
        }
    }

    /**
     * List resources in a namespace by type.
     *
     * @param namespace K8s namespace
     * @param resourceType "pods", "deployments", "services", or "all"
     * @return Map of resource type to list of resource summaries
     */
    fun listResources(namespace: String, resourceType: String = "all"): Map<String, List<Map<String, Any?>>> {
        buildK8sClient().use { client ->
            validateNamespace(client, namespace)
            val result = mutableMapOf<String, List<Map<String, Any?>>>()

            if (resourceType == "all" || resourceType == "pods") {
                result["pods"] = client.pods().inNamespace(namespace).list().items.map { pod ->
                    val status = pod.status
                    mapOf(
                        "name" to pod.metadata.name,
                        "phase" to status?.phase,
                        "ready" to (status?.conditions?.any { it.type == "Ready" && it.status == "True" } ?: false),
                        "restartCount" to (status?.containerStatuses?.sumOf { it.restartCount } ?: 0),
                        "createdAt" to pod.metadata.creationTimestamp,
                    )
                }
            }

            if (resourceType == "all" || resourceType == "deployments") {
                result["deployments"] = client.apps().deployments().inNamespace(namespace).list().items.map { dep ->
                    val status = dep.status
                    mapOf(
                        "name" to dep.metadata.name,
                        "replicas" to (status?.replicas ?: 0),
                        "availableReplicas" to (status?.availableReplicas ?: 0),
                        "ready" to ((status?.availableReplicas ?: 0) > 0),
                        "image" to (dep.spec?.template?.spec?.containers?.firstOrNull()?.image),
                        "createdAt" to dep.metadata.creationTimestamp,
                    )
                }
            }

            if (resourceType == "all" || resourceType == "services") {
                result["services"] = client.services().inNamespace(namespace).list().items.map { svc ->
                    mapOf(
                        "name" to svc.metadata.name,
                        "type" to svc.spec?.type,
                        "clusterIP" to svc.spec?.clusterIP,
                        "ports" to svc.spec?.ports?.map { "${it.port}/${it.protocol}" },
                        "createdAt" to svc.metadata.creationTimestamp,
                    )
                }
            }

            if (resourceType == "all" || resourceType == "secrets") {
                // Return secret NAMES only, never values
                result["secrets"] = client.secrets().inNamespace(namespace).list().items.map { secret ->
                    mapOf(
                        "name" to secret.metadata.name,
                        "type" to secret.type,
                        "keys" to (secret.data?.keys?.toList() ?: emptyList<String>()),
                        "createdAt" to secret.metadata.creationTimestamp,
                    )
                }
            }

            return result
        }
    }

    /**
     * Get pod logs.
     *
     * @param namespace K8s namespace
     * @param podName Pod name
     * @param tailLines Number of recent lines to return (max 1000)
     * @return Log text
     */
    fun getPodLogs(namespace: String, podName: String, tailLines: Int = 100): String {
        val lines = tailLines.coerceIn(1, MAX_LOG_LINES)
        buildK8sClient().use { client ->
            validateNamespace(client, namespace)
            return client.pods()
                .inNamespace(namespace)
                .withName(podName)
                .tailingLines(lines)
                .log
                ?: "No logs available"
        }
    }

    /**
     * Get deployment details including conditions and events.
     */
    fun getDeploymentDetails(namespace: String, name: String): Map<String, Any?> {
        buildK8sClient().use { client ->
            validateNamespace(client, namespace)
            val deployment = client.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .get() ?: throw IllegalArgumentException("Deployment $name not found in $namespace")

            val status = deployment.status
            val conditions = status?.conditions?.map { cond ->
                mapOf(
                    "type" to cond.type,
                    "status" to cond.status,
                    "reason" to cond.reason,
                    "message" to cond.message,
                    "lastTransitionTime" to cond.lastTransitionTime,
                )
            } ?: emptyList()

            // Fetch recent events for this deployment
            val events = client.v1().events()
                .inNamespace(namespace)
                .list().items
                .filter { it.involvedObject?.name == name }
                .sortedByDescending { it.lastTimestamp ?: it.metadata?.creationTimestamp }
                .take(10)
                .map { event ->
                    mapOf(
                        "type" to event.type,
                        "reason" to event.reason,
                        "message" to event.message,
                        "time" to (event.lastTimestamp ?: event.metadata?.creationTimestamp),
                    )
                }

            return mapOf(
                "name" to name,
                "namespace" to namespace,
                "replicas" to (status?.replicas ?: 0),
                "availableReplicas" to (status?.availableReplicas ?: 0),
                "ready" to ((status?.availableReplicas ?: 0) > 0),
                "image" to (deployment.spec?.template?.spec?.containers?.firstOrNull()?.image),
                "conditions" to conditions,
                "events" to events,
                "createdAt" to deployment.metadata.creationTimestamp,
            )
        }
    }

    /**
     * Scale a deployment to the specified number of replicas.
     */
    fun scaleDeployment(namespace: String, name: String, replicas: Int) {
        require(replicas in 0..10) { "Replicas must be between 0 and 10" }
        buildK8sClient().use { client ->
            validateNamespace(client, namespace)
            client.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .scale(replicas)
            logger.info { "K8s: Scaled $name in $namespace to $replicas replicas" }
        }
    }

    /**
     * Trigger a rolling restart of a deployment via annotation update.
     */
    fun restartDeployment(namespace: String, name: String) {
        buildK8sClient().use { client ->
            validateNamespace(client, namespace)
            client.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .rolling()
                .restart()
            logger.info { "K8s: Restarted deployment $name in $namespace" }
        }
    }

    /**
     * Get recent K8s events for a namespace (Warning + Normal events).
     *
     * @param namespace K8s namespace
     * @param limit Max events to return
     * @return List of event maps sorted by time descending
     */
    fun getNamespaceEvents(namespace: String, limit: Int = 50): List<Map<String, Any?>> {
        buildK8sClient().use { client ->
            validateNamespace(client, namespace)
            return client.v1().events()
                .inNamespace(namespace)
                .list().items
                .sortedByDescending { it.lastTimestamp ?: it.metadata?.creationTimestamp }
                .take(limit)
                .map { event ->
                    mapOf(
                        "type" to event.type,
                        "reason" to event.reason,
                        "message" to event.message,
                        "time" to (event.lastTimestamp ?: event.metadata?.creationTimestamp),
                    )
                }
        }
    }

    /**
     * Get overall namespace health status.
     */
    fun getNamespaceStatus(namespace: String): Map<String, Any?> {
        buildK8sClient().use { client ->
            validateNamespace(client, namespace)

            val pods = client.pods().inNamespace(namespace).list().items
            val deployments = client.apps().deployments().inNamespace(namespace).list().items
            val services = client.services().inNamespace(namespace).list().items

            val totalPods = pods.size
            val runningPods = pods.count { it.status?.phase == "Running" }
            val crashingPods = pods.filter { pod ->
                pod.status?.containerStatuses?.any { cs ->
                    cs.state?.waiting?.reason in listOf("CrashLoopBackOff", "Error", "ImagePullBackOff")
                } == true
            }.map { it.metadata.name }

            val totalDeployments = deployments.size
            val readyDeployments = deployments.count { dep ->
                val available = dep.status?.availableReplicas ?: 0
                val desired = dep.status?.replicas ?: 0
                available > 0 && available >= desired
            }

            val healthy = crashingPods.isEmpty() && readyDeployments == totalDeployments && runningPods == totalPods

            return mapOf(
                "namespace" to namespace,
                "healthy" to healthy,
                "pods" to mapOf(
                    "total" to totalPods,
                    "running" to runningPods,
                    "crashing" to crashingPods,
                ),
                "deployments" to mapOf(
                    "total" to totalDeployments,
                    "ready" to readyDeployments,
                ),
                "services" to mapOf(
                    "total" to services.size,
                ),
            )
        }
    }
}
