package com.jervis.service.environment

import com.jervis.common.types.EnvironmentId
import com.jervis.dto.environment.ComponentStatusDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.entity.ComponentType
import com.jervis.entity.EnvironmentDocument
import com.jervis.entity.EnvironmentState
import com.jervis.entity.PropertyMapping
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

    // --- K8s operations (to be implemented with fabric8 or official K8s client) ---

    private fun createNamespace(namespace: String) {
        // TODO: Implement with K8s client
        // kubernetesClient.namespaces().createOrReplace(
        //     NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build()
        // )
        logger.info { "K8s: Creating namespace $namespace" }
    }

    private fun deleteNamespace(namespace: String) {
        // TODO: Implement with K8s client
        logger.info { "K8s: Deleting namespace $namespace" }
    }

    private fun deployComponent(
        namespace: String,
        name: String,
        image: String,
        ports: List<com.jervis.entity.PortMapping>,
        envVars: Map<String, String>,
        cpuLimit: String?,
        memoryLimit: String?,
    ) {
        // TODO: Implement with K8s client
        // Creates: Deployment + Service
        // Deployment: 1 replica, specified image, ports, env vars, resource limits
        // Service: ClusterIP, maps ports for internal DNS ({name}.{namespace}.svc.cluster.local)
        logger.info { "K8s: Deploying $name ($image) in namespace $namespace" }
    }

    private fun deleteComponent(namespace: String, name: String) {
        // TODO: Implement with K8s client
        // Deletes: Deployment + Service
        logger.info { "K8s: Deleting $name from namespace $namespace" }
    }

    private fun getComponentStatus(namespace: String, name: String, componentId: String): ComponentStatusDto {
        // TODO: Implement with K8s client
        // Reads deployment status
        return ComponentStatusDto(
            componentId = componentId,
            name = name,
            ready = false,
            message = "K8s client not yet implemented",
        )
    }
}
