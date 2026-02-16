package com.jervis.service

import com.jervis.dto.environment.K8sDeploymentDetailDto
import com.jervis.dto.environment.K8sNamespaceEventsDto
import com.jervis.dto.environment.K8sNamespaceStatusDto
import com.jervis.dto.environment.K8sResourceListDto
import kotlinx.rpc.annotations.Rpc

/**
 * K8s resource inspection for environment viewer UI.
 * Operates only on Jervis-managed namespaces.
 */
@Rpc
interface IEnvironmentResourceService {
    /** List pods, deployments, services in environment's namespace. */
    suspend fun listResources(environmentId: String): K8sResourceListDto

    /** Get pod logs (tail N lines). */
    suspend fun getPodLogs(environmentId: String, podName: String, tailLines: Int = 100): String

    /** Get deployment details with conditions and events. */
    suspend fun getDeploymentDetails(environmentId: String, deploymentName: String): K8sDeploymentDetailDto

    /** Scale deployment replicas (0-10). */
    suspend fun scaleDeployment(environmentId: String, deploymentName: String, replicas: Int)

    /** Trigger rolling restart. */
    suspend fun restartDeployment(environmentId: String, deploymentName: String)

    /** Get overall namespace health. */
    suspend fun getNamespaceStatus(environmentId: String): K8sNamespaceStatusDto

    /** Get recent K8s events for the namespace (all resources, not just deployments). */
    suspend fun getNamespaceEvents(environmentId: String, limit: Int = 50): K8sNamespaceEventsDto
}
