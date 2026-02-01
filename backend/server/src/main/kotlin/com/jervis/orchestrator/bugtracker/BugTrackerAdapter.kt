package com.jervis.orchestrator.bugtracker

import com.jervis.integration.bugtracker.BugTrackerService
import com.jervis.orchestrator.WorkTrackerAdapter
import com.jervis.orchestrator.model.WorkItem
import com.jervis.orchestrator.model.WorkflowDefinition
import com.jervis.orchestrator.model.WorkflowMapping
import com.jervis.orchestrator.model.WorkflowTransition
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.springframework.stereotype.Component

@Component
class BugTrackerAdapter(
    private val jiraService: BugTrackerService,
) : WorkTrackerAdapter {
    override suspend fun getWorkItem(
        clientId: ClientId,
        itemId: String,
    ): WorkItem {
        val issue = jiraService.getIssue(clientId, itemId)
        return WorkItem(
            id = issue.key,
            type = issue.issueType,
            status = issue.status,
            summary = issue.summary,
            description = issue.description,
            assignee = issue.assignee,
        )
    }

    override suspend fun listChildren(
        clientId: ClientId,
        parentId: String,
    ): List<WorkItem> {
        // V Jira se děti hledají přes JQL (parent = key nebo "Epic Link")
        val issues = jiraService.searchIssues(clientId, "parent = $parentId OR \"Epic Link\" = $parentId")
        return issues.map { issue ->
            WorkItem(
                id = issue.key,
                type = issue.issueType,
                status = issue.status,
                summary = issue.summary,
                description = issue.description,
                assignee = issue.assignee,
            )
        }
    }

    override suspend fun getWorkflow(
        clientId: ClientId,
        itemType: String,
        projectId: ProjectId?,
    ): WorkflowDefinition {
        // TODO: V reálné implementaci by se zde volalo API pro získání workflow
        // Pro účely zadání simulujeme WorkflowDefinition a necháme WorkflowAnalyzer vytvořit mapping

        val mockStates = listOf("To Do", "In Progress", "Code Review", "Done")
        val mockTransitions =
            listOf(
                WorkflowTransition("To Do", "In Progress"),
                WorkflowTransition("In Progress", "Code Review"),
                WorkflowTransition("Code Review", "Done"),
            )

        // Zde by se volal WorkflowAnalyzer pokud mapping nemáme v cache
        // workflowAnalyzer.run(...)

        return WorkflowDefinition(
            itemType = itemType,
            states = mockStates,
            transitions = mockTransitions,
            stateGroups =
                WorkflowMapping(
                    executionReadyStates = setOf("To Do"),
                    doingStates = setOf("In Progress"),
                    reviewStates = setOf("Code Review"),
                    terminalStates = setOf("Done"),
                ),
        )
    }

    override suspend fun getDependencies(
        clientId: ClientId,
        itemId: String,
    ): List<WorkItem> {
        // Hledání linků typu "is blocked by"
        val issues =
            jiraService.searchIssues(
                clientId,
                "issue in linkedIssues($itemId) AND linkedIssueStatus = \"is blocked by\"",
            )
        return issues.map { issue ->
            WorkItem(id = issue.key, type = issue.issueType, status = issue.status, summary = issue.summary)
        }
    }

    override suspend fun suggestComment(
        clientId: ClientId,
        itemId: String,
        comment: String,
    ) {
        jiraService.addComment(clientId, itemId, "JERVIS Suggestion: $comment")
    }

    override suspend fun suggestTransition(
        clientId: ClientId,
        itemId: String,
        toState: String,
    ) {
        // BugTrackerService zatím nepodporuje tranzice, v budoucnu implementujeme
        jiraService.addComment(clientId, itemId, "JERVIS Suggestion: Transition to state $toState")
    }
}
