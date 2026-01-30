package com.jervis.orchestrator

import com.jervis.orchestrator.model.WorkItem
import com.jervis.orchestrator.model.WorkflowDefinition
import com.jervis.types.ClientId
import com.jervis.types.ProjectId

/**
 * WorkTrackerAdapter (Specialist 6.2)
 * 
 * Uniform API over different trackers (Jira, Mantis, etc.)
 */
interface WorkTrackerAdapter {
    suspend fun getWorkItem(clientId: ClientId, itemId: String): WorkItem
    suspend fun listChildren(clientId: ClientId, parentId: String): List<WorkItem>
    suspend fun getWorkflow(clientId: ClientId, itemType: String, projectId: ProjectId?): WorkflowDefinition
    suspend fun getDependencies(clientId: ClientId, itemId: String): List<WorkItem>
    
    // Write operations (drafts/suggestions)
    suspend fun suggestComment(clientId: ClientId, itemId: String, comment: String)
    suspend fun suggestTransition(clientId: ClientId, itemId: String, toState: String)
}
