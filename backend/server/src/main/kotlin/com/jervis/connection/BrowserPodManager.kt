package com.jervis.connection

import com.jervis.common.types.ConnectionId
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ProviderEnum
import kotlinx.coroutines.flow.toList
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.ProbeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.SecretKeySelectorBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Manages dynamic K8s Deployment + Service + PVC per browser connection.
 *
 * Each O365/Teams connection gets its own isolated browser pod:
 *   - Deployment: jervis-browser-{connectionId}
 *   - Service: jervis-browser-{connectionId} (ClusterIP, ports 8090 + 6080)
 *   - PVC: jervis-browser-{connectionId}-data (5Gi, browser profiles)
 *
 * Pod receives CONNECTION_ID via env, reads credentials from MongoDB at startup,
 * and persists init config to PVC for self-restore after cluster restart.
 */
@Service
class BrowserPodManager(
    private val connectionService: ConnectionService,
) {

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val NAMESPACE = "jervis"
        private const val IMAGE = "registry.damek-soft.eu/jandamek/jervis-o365-browser-pool:latest"
        private const val MANAGED_BY = "jervis-browser-pod"

        private const val API_PORT = 8090
        private const val NOVNC_PORT = 6080
        private const val GRPC_PORT = 5501
        private const val STORAGE_SIZE = "5Gi"

        fun deploymentName(connectionId: ConnectionId): String = "jervis-browser-${connectionId}"
        fun serviceName(connectionId: ConnectionId): String = "jervis-browser-${connectionId}"
        fun pvcName(connectionId: ConnectionId): String = "jervis-browser-${connectionId}-data"

        /** HTTP URL for browser-facing routes (/vnc-login, screenshots). */
        fun serviceUrl(connectionId: ConnectionId): String =
            "http://${serviceName(connectionId)}.$NAMESPACE.svc.cluster.local:$API_PORT"

        /** Cluster DNS for the pod-to-pod gRPC channel. */
        fun grpcHost(connectionId: ConnectionId): String =
            "${serviceName(connectionId)}.$NAMESPACE.svc.cluster.local"
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
     * Create Deployment + Service + PVC for a browser connection.
     * Idempotent — uses serverSideApply, safe to call multiple times.
     */
    fun createBrowserPod(connectionId: ConnectionId) {
        val name = deploymentName(connectionId)
        logger.info { "Creating browser pod resources: $name" }

        buildK8sClient().use { client ->
            // 1. PVC for browser profiles
            val pvc = PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(pvcName(connectionId))
                    .withNamespace(NAMESPACE)
                    .addToLabels("app", name)
                    .addToLabels("managed-by", MANAGED_BY)
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .withNewResources()
                        .addToRequests("storage", Quantity(STORAGE_SIZE))
                    .endResources()
                .endSpec()
                .build()

            client.persistentVolumeClaims()
                .inNamespace(NAMESPACE)
                .resource(pvc)
                .serverSideApply()

            // 2. Deployment
            val containerPorts = listOf(
                ContainerPortBuilder().withContainerPort(API_PORT).withName("api").build(),
                ContainerPortBuilder().withContainerPort(NOVNC_PORT).withName("novnc").build(),
                ContainerPortBuilder().withContainerPort(GRPC_PORT).withName("grpc").build(),
            )

            val envVars = listOf(
                EnvVarBuilder().withName("CONNECTION_ID").withValue(connectionId.toString()).build(),
                EnvVarBuilder().withName("O365_BROWSER_POOL_GRPC_PORT").withValue(GRPC_PORT.toString()).build(),
                EnvVarBuilder()
                    .withName("MONGODB_PASSWORD")
                    .withValueFrom(
                        EnvVarSourceBuilder()
                            .withSecretKeyRef(
                                SecretKeySelectorBuilder()
                                    .withName("jervis-secrets")
                                    .withKey("MONGODB_PASSWORD")
                                    .build()
                            )
                            .build()
                    )
                    .build(),
            )

            val volumeMounts = listOf(
                VolumeMountBuilder()
                    .withName("browser-profiles")
                    .withMountPath("/browser-profiles")
                    .build(),
            )

            val volumes = listOf(
                VolumeBuilder()
                    .withName("browser-profiles")
                    .withNewPersistentVolumeClaim()
                        .withClaimName(pvcName(connectionId))
                    .endPersistentVolumeClaim()
                    .build(),
            )

            val livenessProbe = ProbeBuilder()
                .withNewHttpGet()
                    .withPath("/health")
                    .withPort(IntOrString(API_PORT))
                .endHttpGet()
                .withInitialDelaySeconds(20)
                .withPeriodSeconds(30)
                .withTimeoutSeconds(5)
                .withFailureThreshold(3)
                .build()

            val readinessProbe = ProbeBuilder()
                .withNewHttpGet()
                    .withPath("/ready")
                    .withPort(IntOrString(API_PORT))
                .endHttpGet()
                .withInitialDelaySeconds(15)
                .withPeriodSeconds(10)
                .withTimeoutSeconds(5)
                .withFailureThreshold(3)
                .build()

            val deployment = DeploymentBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(NAMESPACE)
                    .addToLabels("app", name)
                    .addToLabels("managed-by", MANAGED_BY)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withNewStrategy()
                        .withType("Recreate")
                    .endStrategy()
                    .withNewSelector()
                        .addToMatchLabels("app", name)
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels("app", name)
                            .addToLabels("managed-by", MANAGED_BY)
                        .endMetadata()
                        .withNewSpec()
                            .withServiceAccountName("default")
                            .addNewContainer()
                                .withName("browser")
                                .withImage(IMAGE)
                                .withImagePullPolicy("Always")
                                .withPorts(containerPorts)
                                .withEnv(envVars)
                                .withEnvFrom(
                                    io.fabric8.kubernetes.api.model.EnvFromSourceBuilder()
                                        .withNewConfigMapRef()
                                            .withName("jervis-config")
                                        .endConfigMapRef()
                                        .build()
                                )
                                .withVolumeMounts(volumeMounts)
                                .withLivenessProbe(livenessProbe)
                                .withReadinessProbe(readinessProbe)
                                .withNewResources()
                                    .addToRequests("memory", Quantity("2Gi"))
                                    .addToRequests("cpu", Quantity("1000m"))
                                    .addToLimits("memory", Quantity("8Gi"))
                                    .addToLimits("cpu", Quantity("4000m"))
                                .endResources()
                            .endContainer()
                            .withVolumes(volumes)
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build()

            client.apps().deployments()
                .inNamespace(NAMESPACE)
                .resource(deployment)
                .serverSideApply()

            // 3. Service (ClusterIP for internal DNS)
            val servicePorts = listOf(
                ServicePortBuilder().withName("api").withPort(API_PORT).withTargetPort(IntOrString(API_PORT)).build(),
                ServicePortBuilder().withName("novnc").withPort(NOVNC_PORT).withTargetPort(IntOrString(NOVNC_PORT)).build(),
                ServicePortBuilder().withName("grpc").withPort(GRPC_PORT).withTargetPort(IntOrString(GRPC_PORT)).build(),
            )

            val service = ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName(connectionId))
                    .withNamespace(NAMESPACE)
                    .addToLabels("managed-by", MANAGED_BY)
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .addToSelector("app", name)
                    .withPorts(servicePorts)
                .endSpec()
                .build()

            client.services()
                .inNamespace(NAMESPACE)
                .resource(service)
                .serverSideApply()

            logger.info { "Browser pod resources created: $name (deployment + service + pvc)" }
        }
    }

    /**
     * Delete all K8s resources for a browser connection.
     */
    fun deleteBrowserPod(connectionId: ConnectionId) {
        val name = deploymentName(connectionId)
        logger.info { "Deleting browser pod resources: $name" }

        buildK8sClient().use { client ->
            client.apps().deployments().inNamespace(NAMESPACE).withName(name).delete()
            client.services().inNamespace(NAMESPACE).withName(serviceName(connectionId)).delete()
            client.persistentVolumeClaims().inNamespace(NAMESPACE).withName(pvcName(connectionId)).delete()
            logger.info { "Browser pod resources deleted: $name (deployment + service + pvc)" }
        }
    }

    /**
     * Rolling restart of a browser pod (e.g. after credential change).
     */
    fun restartBrowserPod(connectionId: ConnectionId) {
        val name = deploymentName(connectionId)
        logger.info { "Restarting browser pod: $name" }

        buildK8sClient().use { client ->
            val deployment = client.apps().deployments()
                .inNamespace(NAMESPACE)
                .withName(name)
                .get()

            if (deployment != null) {
                // Trigger rolling restart via annotation update
                val annotations = deployment.spec?.template?.metadata?.annotations?.toMutableMap()
                    ?: mutableMapOf()
                annotations["kubectl.kubernetes.io/restartedAt"] = java.time.Instant.now().toString()
                deployment.spec.template.metadata.annotations = annotations

                client.apps().deployments()
                    .inNamespace(NAMESPACE)
                    .resource(deployment)
                    .serverSideApply()

                logger.info { "Browser pod restarted: $name" }
            } else {
                logger.warn { "Browser pod not found for restart: $name — creating instead" }
                createBrowserPod(connectionId)
            }
        }
    }

    /**
     * Check if browser pod resources exist.
     */
    fun podExists(connectionId: ConnectionId): Boolean {
        val name = deploymentName(connectionId)
        return buildK8sClient().use { client ->
            client.apps().deployments()
                .inNamespace(NAMESPACE)
                .withName(name)
                .get() != null
        }
    }

    /**
     * Ensure browser pod exists — create if missing.
     */
    fun ensureBrowserPod(connectionId: ConnectionId) {
        if (!podExists(connectionId)) {
            logger.info { "Browser pod missing for connection $connectionId — creating" }
            createBrowserPod(connectionId)
        }
    }

    /**
     * Reconcile browser pods on server startup.
     * Ensures every Teams browser-session connection has a running pod.
     * After pod creation, waits for readiness and sends init with credentials.
     */
    @jakarta.annotation.PostConstruct
    fun reconcileOnStartup() {
        kotlinx.coroutines.runBlocking {
            val connections = connectionService.findAll()
                .toList()
                .filter { it.provider == ProviderEnum.MICROSOFT_TEAMS && it.authType == AuthTypeEnum.NONE }

            if (connections.isEmpty()) {
                logger.info { "No browser session connections to reconcile" }
                return@runBlocking
            }

            logger.info { "Reconciling ${connections.size} browser session connection(s)" }
            for (conn in connections) {
                try {
                    ensureBrowserPod(conn.id)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to reconcile browser pod for connection ${conn.id}" }
                }
            }
            logger.info { "Browser pod reconciliation complete" }
        }
    }
}
