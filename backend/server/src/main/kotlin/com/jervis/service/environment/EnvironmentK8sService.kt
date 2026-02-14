package com.jervis.service.environment

import com.jervis.common.types.EnvironmentId
import com.jervis.dto.environment.ComponentStatusDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.entity.ComponentType
import com.jervis.entity.EnvironmentDocument
import com.jervis.entity.EnvironmentState
import com.jervis.entity.PortMapping
import com.jervis.entity.PropertyMapping
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Manages K8s namespaces and infrastructure component deployment.
 *
 * Creates namespaces, deploys infrastructure (PostgreSQL, Redis, etc.) as K8s Deployments+Services,
 * resolves property mapping templates with actual K8s service endpoints.
 *
 * Does NOT start PROJECT components – that is the agent's responsibility.
 */
@Service
class EnvironmentK8sService(
    private val environmentService: EnvironmentService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
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

            // 2. Deploy infrastructure components (sorted by startOrder)
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

                deployComponent(
                    namespace = env.namespace,
                    name = component.name,
                    image = image,
                    ports = ports,
                    envVars = envVars,
                    cpuLimit = component.cpuLimit,
                    memoryLimit = component.memoryLimit,
                )
                logger.info { "Deployed component: ${component.name} (${component.type})" }
            }

            // 3. Resolve property mapping templates
            val resolvedMappings = env.propertyMappings.map { mapping ->
                val targetComponent = env.components.find { it.id == mapping.targetComponentId }
                if (targetComponent != null) {
                    val host = "${targetComponent.name}.${env.namespace}.svc.cluster.local"
                    val port = targetComponent.ports.firstOrNull()?.let { it.servicePort ?: it.containerPort }
                    val resolved = mapping.valueTemplate
                        .replace("{host}", host)
                        .replace("{port}", port?.toString() ?: "")
                        .replace("{name}", targetComponent.name)
                        .replace("{namespace}", env.namespace)
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
            // Delete infrastructure components
            val infraComponents = env.components
                .filter { it.type != ComponentType.PROJECT }

            for (component in infraComponents) {
                deleteComponent(env.namespace, component.name)
                logger.info { "Deleted component: ${component.name}" }
            }

            if (deleteNamespace) {
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

    private fun deployComponent(
        namespace: String,
        name: String,
        image: String,
        ports: List<PortMapping>,
        envVars: Map<String, String>,
        cpuLimit: String?,
        memoryLimit: String?,
    ) {
        buildK8sClient().use { client ->
            // 1. Create Deployment
            val containerPorts = ports.map { pm ->
                ContainerPortBuilder()
                    .withContainerPort(pm.containerPort)
                    .withName(pm.name.ifEmpty { "port-${pm.containerPort}" })
                    .build()
            }
            val envList = envVars.map { (k, v) ->
                EnvVarBuilder().withName(k).withValue(v).build()
            }

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
                                .withEnv(envList)
                                .withNewResources()
                                    .addToLimits(buildResourceMap(cpuLimit, memoryLimit))
                                .endResources()
                            .endContainer()
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
            logger.info { "K8s: Deleted $name from namespace $namespace" }
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
