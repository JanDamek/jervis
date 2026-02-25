package com.jervis.dto.environment

import kotlinx.serialization.Serializable

/**
 * EPIC 17: Environment Agent Enhancement DTOs.
 *
 * Supports on-demand environment agents for K8s debugging,
 * deployment validation, and log analysis.
 */

/**
 * Environment agent job types.
 */
@Serializable
enum class EnvironmentAgentJobType {
    /** Run deployment validation (health check + smoke test + log analysis). */
    DEPLOYMENT_VALIDATION,
    /** Fetch and parse pod logs. */
    DEBUG_LOGS,
    /** Run kubectl describe for pod status/events. */
    DEBUG_STATUS,
    /** General environment agent task. */
    GENERAL,
}

/**
 * Request to dispatch an environment agent job.
 */
@Serializable
data class EnvironmentAgentRequest(
    val jobType: EnvironmentAgentJobType,
    val namespace: String,
    val targetResource: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val clientId: String,
    val projectId: String,
    val parentTaskId: String? = null,
)

/**
 * Result of deployment validation.
 */
@Serializable
data class DeploymentValidationResult(
    val healthy: Boolean,
    val podCount: Int = 0,
    val readyCount: Int = 0,
    val errorPods: List<String> = emptyList(),
    val smokeTestPassed: Boolean? = null,
    val logErrors: List<String> = emptyList(),
    val events: List<String> = emptyList(),
)
