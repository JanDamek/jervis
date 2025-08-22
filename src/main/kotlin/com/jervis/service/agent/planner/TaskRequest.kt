package com.jervis.service.agent.planner

/**
 * Task request for Planner. Contains user context hints and flags.
 */
data class TaskRequest(
    val clientName: String? = null,
    val projectName: String? = null, // empty or null means auto-scope / unknown
    val autoScope: Boolean = false,
    val text: String,
)
