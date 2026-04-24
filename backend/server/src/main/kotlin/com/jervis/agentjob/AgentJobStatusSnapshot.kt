package com.jervis.agentjob

/**
 * Read-only view combining the persisted [AgentJobRecord] with the current
 * Kubernetes Job phase (when a Job exists).
 *
 * Exposed via `AgentJobDispatcher.getStatus(id)` and used by MCP tool
 * `get_agent_job_status` (3b.5) so Claude can tell "mongo says RUNNING but
 * the Pod is actually gone" without scraping kubectl itself. The watcher
 * reconciles this mismatch eventually, but during the gap between K8s Job
 * completion and the next watcher tick, callers may see a live record in
 * RUNNING while the Job is already gone — `kubernetesJobPhase=null` is
 * then the signal that reconciliation is pending.
 */
data class AgentJobStatusSnapshot(
    val record: AgentJobRecord,
    /**
     * K8s Job phase per `Job.status.phase` / computed status:
     *   "Active", "Succeeded", "Failed", "Suspended", or null when the Job
     *   object no longer exists (or was never created because the record
     *   is still QUEUED / terminal before dispatch).
     */
    val kubernetesJobPhase: String?,
)
