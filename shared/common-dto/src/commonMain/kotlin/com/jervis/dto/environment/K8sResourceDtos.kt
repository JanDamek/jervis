package com.jervis.dto.environment

import kotlinx.serialization.Serializable

/**
 * K8s resource listing for environment viewer.
 */
@Serializable
data class K8sResourceListDto(
    val pods: List<K8sPodDto> = emptyList(),
    val deployments: List<K8sDeploymentDto> = emptyList(),
    val services: List<K8sServiceDto> = emptyList(),
)

@Serializable
data class K8sPodDto(
    val name: String,
    val phase: String? = null,
    val ready: Boolean = false,
    val restartCount: Int = 0,
    val createdAt: String? = null,
)

@Serializable
data class K8sDeploymentDto(
    val name: String,
    val replicas: Int = 0,
    val availableReplicas: Int = 0,
    val ready: Boolean = false,
    val image: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class K8sServiceDto(
    val name: String,
    val type: String? = null,
    val clusterIP: String? = null,
    val ports: List<String> = emptyList(),
    val createdAt: String? = null,
)

@Serializable
data class K8sNamespaceStatusDto(
    val namespace: String,
    val healthy: Boolean = false,
    val totalPods: Int = 0,
    val runningPods: Int = 0,
    val crashingPods: List<String> = emptyList(),
    val totalDeployments: Int = 0,
    val readyDeployments: Int = 0,
    val totalServices: Int = 0,
)

@Serializable
data class K8sDeploymentDetailDto(
    val name: String,
    val namespace: String,
    val replicas: Int = 0,
    val availableReplicas: Int = 0,
    val ready: Boolean = false,
    val image: String? = null,
    val createdAt: String? = null,
    val conditions: List<K8sConditionDto> = emptyList(),
    val events: List<K8sEventDto> = emptyList(),
)

@Serializable
data class K8sConditionDto(
    val type: String,
    val status: String,
    val reason: String? = null,
    val message: String? = null,
    val lastTransitionTime: String? = null,
)

@Serializable
data class K8sEventDto(
    val type: String? = null,
    val reason: String? = null,
    val message: String? = null,
    val time: String? = null,
)

/**
 * Namespace-level K8s events (not tied to a specific deployment).
 */
@Serializable
data class K8sNamespaceEventsDto(
    val namespace: String,
    val events: List<K8sEventDto> = emptyList(),
)
