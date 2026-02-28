package com.jervis.service.environment

import com.jervis.common.types.EnvironmentId
import com.jervis.dto.environment.ComponentStatusDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.entity.ComponentType
import com.jervis.entity.EnvironmentComponent
import com.jervis.entity.EnvironmentDocument
import com.jervis.entity.EnvironmentState
import com.jervis.entity.PortMapping
import com.jervis.entity.PropertyMapping
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Probe
import io.fabric8.kubernetes.api.model.ProbeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Manages K8s namespaces and infrastructure component deployment.
 *
 * Creates namespaces, deploys infrastructure (PostgreSQL, Redis, etc.) as K8s Deployments+Services,
 * resolves property mapping templates with actual K8s service endpoints.
 *
 * Features:
 * - One PVC per environment (shared via subPath per component)
 * - One ConfigMap per component (envVars externalized from Deployment)
 * - Health probes (liveness + readiness) from ComponentDefaults
 * - Sync mechanism: re-apply ConfigMaps + Deployments for RUNNING environments
 *
 * Does NOT start PROJECT components – that is the agent's responsibility.
 */
@Service
class EnvironmentK8sService(
    private val environmentService: EnvironmentService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        /** Generate a per-environment PVC name from the namespace. */
        fun pvcName(namespace: String): String = "env-data-$namespace"
    }

    /**
     * Create K8s namespace and deploy infrastructure components.
     * Returns updated environment with resolved property values and RUNNING state.
     */
    suspend fun provisionEnvironment(environmentId: EnvironmentId): EnvironmentDocument {
        val env = environmentService.getEnvironmentById(environmentId)

        logger.info { "Provisioning environment: ${env.name} (ns=${env.namespace})" }
        environmentService.updateState(environmentId, EnvironmentState.CREATING)

        try {
            // 1. Create namespace
            createNamespace(env.namespace)

            // 2. Create shared PVC if any component needs persistent storage
            val hasStorage = env.components.any { comp ->
                comp.type != ComponentType.PROJECT &&
                    (comp.volumeMountPath ?: COMPONENT_DEFAULTS[comp.type]?.defaultVolumeMountPath) != null
            }
            if (hasStorage) {
                createOrUpdatePvc(env.namespace, env.storageSizeGi)
            }

            // 3. Deploy infrastructure components (sorted by startOrder)
            val infraComponents = env.components
                .filter { it.type != ComponentType.PROJECT && it.autoStart }
                .sortedBy { it.startOrder }

            for (component in infraComponents) {
                val image = component.image
                    ?: COMPONENT_DEFAULTS[component.type]?.image
                    ?: throw IllegalStateException("No image specified for component ${component.name} (type=${component.type})")

                val ports = component.ports.ifEmpty {
                    COMPONENT_DEFAULTS[component.type]?.ports ?: emptyList()
                }

                val envVars = (COMPONENT_DEFAULTS[component.type]?.defaultEnvVars ?: emptyMap()) + component.envVars

                // Create ConfigMap for this component's env vars
                if (envVars.isNotEmpty()) {
                    createOrUpdateConfigMap(env.namespace, component.name, envVars)
                }

                val volumeMountPath = component.volumeMountPath
                    ?: COMPONENT_DEFAULTS[component.type]?.defaultVolumeMountPath

                // Resolve health probe: user override via healthCheckPath, or default from ComponentDefaults
                val probeConfig = resolveProbeConfig(component)

                deployComponent(
                    namespace = env.namespace,
                    name = component.name,
                    image = image,
                    ports = ports,
                    envVars = envVars,
                    cpuLimit = component.cpuLimit,
                    memoryLimit = component.memoryLimit,
                    volumeMountPath = volumeMountPath,
                    probeConfig = probeConfig,
                )
                logger.info { "Deployed component: ${component.name} (${component.type})" }
            }

            // 4. Resolve property mapping templates
            val resolvedMappings = env.propertyMappings.map { mapping ->
                val targetComponent = env.components.find { it.id == mapping.targetComponentId }
                if (targetComponent != null) {
                    val host = "${targetComponent.name}.${env.namespace}.svc.cluster.local"
                    val port = targetComponent.ports.firstOrNull()?.let { it.servicePort ?: it.containerPort }
                    var resolved = mapping.valueTemplate
                        .replace("{host}", host)
                        .replace("{port}", port?.toString() ?: "")
                        .replace("{name}", targetComponent.name)
                        .replace("{namespace}", env.namespace)
                    // Resolve {env:VAR_NAME} placeholders from target component's env vars
                    resolved = Regex("\\{env:([^}]+)}").replace(resolved) { matchResult ->
                        val varName = matchResult.groupValues[1]
                        targetComponent.envVars[varName] ?: matchResult.value
                    }
                    mapping.copy(resolvedValue = resolved)
                } else {
                    mapping
                }
            }

            environmentService.updateResolvedValues(environmentId, resolvedMappings)
            val result = environmentService.updateState(environmentId, EnvironmentState.RUNNING)

            logger.info { "Environment provisioned: ${env.name} (ns=${env.namespace})" }
            return result
        } catch (e: Exception) {
            logger.error(e) { "Failed to provision environment: ${env.name}" }
            environmentService.updateState(environmentId, EnvironmentState.ERROR)
            throw e
        }
    }

    /**
     * Tear down infrastructure components and optionally delete namespace.
     */
    suspend fun deprovisionEnvironment(
        environmentId: EnvironmentId,
        deleteNamespace: Boolean = false,
    ): EnvironmentDocument {
        val env = environmentService.getEnvironmentById(environmentId)

        logger.info { "Deprovisioning environment: ${env.name} (ns=${env.namespace})" }
        environmentService.updateState(environmentId, EnvironmentState.STOPPING)

        try {
            // Delete infrastructure components + their ConfigMaps
            val infraComponents = env.components
                .filter { it.type != ComponentType.PROJECT }

            for (component in infraComponents) {
                deleteComponent(env.namespace, component.name)
                logger.info { "Deleted component: ${component.name}" }
            }

            // Delete PVC if deleting namespace (otherwise keep data for re-provisioning)
            if (deleteNamespace) {
                deletePvc(env.namespace)
                deleteNamespace(env.namespace)
            }

            val result = environmentService.updateState(environmentId, EnvironmentState.STOPPED)
            logger.info { "Environment deprovisioned: ${env.name}" }
            return result
        } catch (e: Exception) {
            logger.error(e) { "Failed to deprovision environment: ${env.name}" }
            environmentService.updateState(environmentId, EnvironmentState.ERROR)
            throw e
        }
    }

    /**
     * Sync K8s resources for a RUNNING environment.
     * Re-applies ConfigMaps and Deployments with current settings.
     * Idempotent via serverSideApply.
     */
    suspend fun syncEnvironmentResources(environmentId: EnvironmentId): EnvironmentDocument {
        val env = environmentService.getEnvironmentById(environmentId)

        if (env.state != EnvironmentState.RUNNING) {
            throw IllegalStateException("Cannot sync resources for environment in state ${env.state}")
        }

        logger.info { "Syncing resources for environment: ${env.name} (ns=${env.namespace})" }

        try {
            // Ensure namespace exists before syncing (auto-create if missing — BUG FIX)
            ensureNamespaceExists(env.namespace)

            val infraComponents = env.components
                .filter { it.type != ComponentType.PROJECT && it.autoStart }
                .sortedBy { it.startOrder }

            for (component in infraComponents) {
                val image = component.image
                    ?: COMPONENT_DEFAULTS[component.type]?.image
                    ?: continue

                val ports = component.ports.ifEmpty {
                    COMPONENT_DEFAULTS[component.type]?.ports ?: emptyList()
                }

                val envVars = (COMPONENT_DEFAULTS[component.type]?.defaultEnvVars ?: emptyMap()) + component.envVars

                // Update ConfigMap
                if (envVars.isNotEmpty()) {
                    createOrUpdateConfigMap(env.namespace, component.name, envVars)
                }

                val volumeMountPath = component.volumeMountPath
                    ?: COMPONENT_DEFAULTS[component.type]?.defaultVolumeMountPath

                val probeConfig = resolveProbeConfig(component)

                // Re-apply Deployment (serverSideApply = idempotent)
                deployComponent(
                    namespace = env.namespace,
                    name = component.name,
                    image = image,
                    ports = ports,
                    envVars = envVars,
                    cpuLimit = component.cpuLimit,
                    memoryLimit = component.memoryLimit,
                    volumeMountPath = volumeMountPath,
                    probeConfig = probeConfig,
                )
                logger.info { "Synced component: ${component.name}" }
            }

            // Re-resolve property mappings (component ENV vars may have changed)
            val resolvedMappings = env.propertyMappings.map { mapping ->
                val targetComponent = env.components.find { it.id == mapping.targetComponentId }
                if (targetComponent != null) {
                    val host = "${targetComponent.name}.${env.namespace}.svc.cluster.local"
                    val port = targetComponent.ports.firstOrNull()?.let { it.servicePort ?: it.containerPort }
                    var resolved = mapping.valueTemplate
                        .replace("{host}", host)
                        .replace("{port}", port?.toString() ?: "")
                        .replace("{name}", targetComponent.name)
                        .replace("{namespace}", env.namespace)
                    resolved = Regex("\\{env:([^}]+)}").replace(resolved) { matchResult ->
                        val varName = matchResult.groupValues[1]
                        targetComponent.envVars[varName] ?: matchResult.value
                    }
                    mapping.copy(resolvedValue = resolved)
                } else {
                    mapping
                }
            }
            environmentService.updateResolvedValues(environmentId, resolvedMappings)

            logger.info { "Resources synced for environment: ${env.name}" }
            return environmentService.getEnvironmentById(environmentId)
        } catch (e: Exception) {
            logger.error(e) { "Failed to sync resources for environment: ${env.name}" }
            throw e
        }
    }

    /**
     * Get status of all components in the environment.
     */
    suspend fun getEnvironmentStatus(environmentId: EnvironmentId): EnvironmentStatusDto {
        val env = environmentService.getEnvironmentById(environmentId)

        val componentStatuses = env.components
            .filter { it.type != ComponentType.PROJECT }
            .map { component ->
                getComponentStatus(env.namespace, component.name, component.id)
            }

        return EnvironmentStatusDto(
            environmentId = env.id.toString(),
            namespace = env.namespace,
            state = EnvironmentStateEnum.valueOf(env.state.name),
            componentStatuses = componentStatuses,
        )
    }

    // --- Helper: resolve probe config ---

    private fun resolveProbeConfig(component: EnvironmentComponent): HealthProbeConfig? {
        // User override: healthCheckPath → HTTP probe on first port
        if (!component.healthCheckPath.isNullOrBlank()) {
            val port = component.ports.firstOrNull()?.containerPort
                ?: COMPONENT_DEFAULTS[component.type]?.ports?.firstOrNull()?.containerPort
                ?: return null
            return HealthProbeConfig(
                type = ProbeType.HTTP,
                port = port,
                path = component.healthCheckPath,
                initialDelaySeconds = 30,
            )
        }
        // Default from ComponentDefaults
        return COMPONENT_DEFAULTS[component.type]?.healthProbe
    }

    // --- K8s operations via fabric8 client ---

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
     * Check if namespace exists and auto-create it if missing.
     * Called on environment save to ensure K8s resources tab can list resources,
     * and on sync to recover from deleted namespaces.
     */
    fun ensureNamespaceExists(namespace: String) {
        buildK8sClient().use { client ->
            val ns = client.namespaces().withName(namespace).get()
            if (ns == null) {
                logger.warn { "K8s: Namespace $namespace does not exist — auto-creating for environment sync" }
                createNamespace(namespace)
            }
        }
    }

    private fun createNamespace(namespace: String) {
        buildK8sClient().use { client ->
            val ns = NamespaceBuilder()
                .withNewMetadata()
                    .withName(namespace)
                    .addToLabels("app", "jervis")
                    .addToLabels("managed-by", "jervis-server")
                .endMetadata()
                .build()
            client.namespaces().resource(ns).serverSideApply()
            logger.info { "K8s: Created namespace $namespace" }
        }
    }

    private fun deleteNamespace(namespace: String) {
        buildK8sClient().use { client ->
            // Safety: only delete namespaces managed by Jervis
            val ns = client.namespaces().withName(namespace).get()
            if (ns?.metadata?.labels?.get("managed-by") != "jervis-server") {
                throw IllegalStateException(
                    "Namespace $namespace is not managed by Jervis (missing managed-by label), refusing to delete"
                )
            }
            client.namespaces().withName(namespace).delete()
            logger.info { "K8s: Deleted namespace $namespace" }
        }
    }

    // --- PVC operations ---

    private fun createOrUpdatePvc(namespace: String, sizeGi: Int) {
        buildK8sClient().use { client ->
            // Check if PVC already exists (PVC size cannot be reduced)
            val existing = client.persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(pvcName(namespace))
                .get()

            if (existing != null) {
                logger.info { "K8s: PVC ${pvcName(namespace)} already exists in $namespace" }
                return
            }

            val pvc = PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(pvcName(namespace))
                    .withNamespace(namespace)
                    .addToLabels("app", "jervis")
                    .addToLabels("managed-by", "jervis-server")
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .withNewResources()
                        .addToRequests("storage", Quantity("${sizeGi}Gi"))
                    .endResources()
                .endSpec()
                .build()

            client.persistentVolumeClaims()
                .inNamespace(namespace)
                .resource(pvc)
                .create()

            logger.info { "K8s: Created PVC ${pvcName(namespace)} (${sizeGi}Gi) in $namespace" }
        }
    }

    private fun deletePvc(namespace: String) {
        buildK8sClient().use { client ->
            client.persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(pvcName(namespace))
                .delete()
            logger.info { "K8s: Deleted PVC ${pvcName(namespace)} from $namespace" }
        }
    }

    // --- ConfigMap operations ---

    private fun createOrUpdateConfigMap(namespace: String, componentName: String, envVars: Map<String, String>) {
        buildK8sClient().use { client ->
            val configMapName = "$componentName-config"
            val configMap = ConfigMapBuilder()
                .withNewMetadata()
                    .withName(configMapName)
                    .withNamespace(namespace)
                    .addToLabels("app", componentName)
                    .addToLabels("managed-by", "jervis-server")
                .endMetadata()
                .also { builder -> envVars.forEach { (k, v) -> builder.addToData(k, v) } }
                .build()

            try {
                client.configMaps()
                    .inNamespace(namespace)
                    .resource(configMap)
                    .serverSideApply()
                logger.info { "K8s: Applied ConfigMap $configMapName in $namespace" }
            } catch (e: io.fabric8.kubernetes.client.KubernetesClientException) {
                if (e.code == 404) {
                    throw IllegalStateException(
                        "Namespace '$namespace' does not exist in K8s cluster. " +
                            "Provision the environment first or verify the namespace was created correctly.",
                        e,
                    )
                }
                throw e
            }
        }
    }

    private fun deleteConfigMap(namespace: String, componentName: String) {
        buildK8sClient().use { client ->
            client.configMaps()
                .inNamespace(namespace)
                .withName("$componentName-config")
                .delete()
        }
    }

    // --- Probe builder ---

    private fun buildProbe(config: HealthProbeConfig): Probe {
        val builder = ProbeBuilder()
            .withInitialDelaySeconds(config.initialDelaySeconds)
            .withPeriodSeconds(config.periodSeconds)
            .withTimeoutSeconds(5)
            .withFailureThreshold(3)

        return when (config.type) {
            ProbeType.TCP -> builder
                .withNewTcpSocket()
                    .withPort(IntOrString(config.port))
                .endTcpSocket()
                .build()

            ProbeType.HTTP -> builder
                .withNewHttpGet()
                    .withPath(config.path ?: "/")
                    .withPort(IntOrString(config.port))
                .endHttpGet()
                .build()
        }
    }

    // --- Deploy / delete component ---

    private fun deployComponent(
        namespace: String,
        name: String,
        image: String,
        ports: List<PortMapping>,
        envVars: Map<String, String>,
        cpuLimit: String?,
        memoryLimit: String?,
        volumeMountPath: String? = null,
        probeConfig: HealthProbeConfig? = null,
    ) {
        buildK8sClient().use { client ->
            // 1. Create Deployment
            val containerPorts = ports.map { pm ->
                ContainerPortBuilder()
                    .withContainerPort(pm.containerPort)
                    .withName(pm.name.ifEmpty { "port-${pm.containerPort}" })
                    .build()
            }

            // Volume mounts for the container
            val volumeMounts = mutableListOf<io.fabric8.kubernetes.api.model.VolumeMount>()
            if (volumeMountPath != null) {
                volumeMounts.add(
                    VolumeMountBuilder()
                        .withName(pvcName(namespace))
                        .withMountPath(volumeMountPath)
                        .withSubPath(name) // subPath isolates each component's data on the shared PVC
                        .build()
                )
            }

            // Pod volumes
            val volumes = mutableListOf<io.fabric8.kubernetes.api.model.Volume>()
            if (volumeMountPath != null) {
                volumes.add(
                    VolumeBuilder()
                        .withName(pvcName(namespace))
                        .withNewPersistentVolumeClaim()
                            .withClaimName(pvcName(namespace))
                        .endPersistentVolumeClaim()
                        .build()
                )
            }

            // EnvFrom — load all env vars from ConfigMap (if any exist)
            val envFromSources = if (envVars.isNotEmpty()) {
                listOf(
                    EnvFromSourceBuilder()
                        .withNewConfigMapRef()
                            .withName("$name-config")
                        .endConfigMapRef()
                        .build()
                )
            } else {
                emptyList()
            }

            // Probes
            val livenessProbe = probeConfig?.let { buildProbe(it) }
            val readinessProbe = probeConfig?.let { buildProbe(it) }

            val deploymentBuilder = DeploymentBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels("app", name)
                    .addToLabels("managed-by", "jervis-server")
                .endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withNewSelector()
                        .addToMatchLabels("app", name)
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels("app", name)
                        .endMetadata()
                        .withNewSpec()
                            .addNewContainer()
                                .withName(name)
                                .withImage(image)
                                .withPorts(containerPorts)
                                .withEnvFrom(envFromSources)
                                .withVolumeMounts(volumeMounts)
                                .withLivenessProbe(livenessProbe)
                                .withReadinessProbe(readinessProbe)
                                .withNewResources()
                                    .addToLimits(buildResourceMap(cpuLimit, memoryLimit))
                                .endResources()
                            .endContainer()
                            .withVolumes(volumes)
                        .endSpec()
                    .endTemplate()
                .endSpec()

            client.apps().deployments()
                .inNamespace(namespace)
                .resource(deploymentBuilder.build())
                .serverSideApply()

            // 2. Create Service (ClusterIP for internal DNS)
            val servicePorts = ports.map { pm ->
                ServicePortBuilder()
                    .withPort(pm.servicePort ?: pm.containerPort)
                    .withTargetPort(IntOrString(pm.containerPort))
                    .withName(pm.name.ifEmpty { "port-${pm.containerPort}" })
                    .build()
            }

            val service = ServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels("managed-by", "jervis-server")
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .addToSelector("app", name)
                    .withPorts(servicePorts)
                .endSpec()
                .build()

            client.services()
                .inNamespace(namespace)
                .resource(service)
                .serverSideApply()

            logger.info { "K8s: Deployed $name ($image) in namespace $namespace" }
        }
    }

    private fun deleteComponent(namespace: String, name: String) {
        buildK8sClient().use { client ->
            client.apps().deployments().inNamespace(namespace).withName(name).delete()
            client.services().inNamespace(namespace).withName(name).delete()
            // Delete component ConfigMap
            client.configMaps().inNamespace(namespace).withName("$name-config").delete()
            logger.info { "K8s: Deleted $name (deployment + service + configmap) from namespace $namespace" }
        }
    }

    private fun getComponentStatus(namespace: String, name: String, componentId: String): ComponentStatusDto {
        return try {
            buildK8sClient().use { client ->
                val deployment = client.apps().deployments()
                    .inNamespace(namespace)
                    .withName(name)
                    .get()

                if (deployment == null) {
                    return ComponentStatusDto(
                        componentId = componentId,
                        name = name,
                        ready = false,
                        message = "Deployment not found",
                    )
                }

                val status = deployment.status
                val replicas = status?.replicas ?: 0
                val available = status?.availableReplicas ?: 0
                val message = status?.conditions
                    ?.firstOrNull { it.type == "Available" }
                    ?.message

                ComponentStatusDto(
                    componentId = componentId,
                    name = name,
                    ready = available > 0 && available >= replicas,
                    replicas = replicas,
                    availableReplicas = available,
                    message = message,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get status for $name in $namespace" }
            ComponentStatusDto(
                componentId = componentId,
                name = name,
                ready = false,
                message = "Error: ${e.message}",
            )
        }
    }

    private fun buildResourceMap(cpuLimit: String?, memoryLimit: String?): Map<String, Quantity> {
        val map = mutableMapOf<String, Quantity>()
        cpuLimit?.let { map["cpu"] = Quantity(it) }
        memoryLimit?.let { map["memory"] = Quantity(it) }
        return map
    }
}
