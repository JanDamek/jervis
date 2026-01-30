package com.jervis.orchestrator.agents

import com.jervis.orchestrator.WorkTrackerAdapter
import com.jervis.orchestrator.model.*
import com.jervis.types.ClientId
import org.springframework.stereotype.Component

/**
 * ProgramManager (Specialist 6.7)
 * 
 * Responsibilities:
 * - Handle EPIC/backlog execution
 * - Readiness pass (workflow-aware)
 * - Dependency graph + waves + queue
 */
@Component
class ProgramManager(
    private val tracker: WorkTrackerAdapter
) {
    suspend fun planEpic(clientId: ClientId, epicId: String): ProgramState {
        val children = tracker.listChildren(clientId, epicId)
        val allTasks = children.map { item ->
            val deps = tracker.getDependencies(clientId, item.id)
            val workflow = tracker.getWorkflow(clientId, item.type, null)
            
            val readiness = evaluateReadiness(item, deps, workflow)
            
            WorkItemState(
                id = item.id,
                type = item.type,
                status = item.status,
                title = item.summary,
                readiness = readiness,
                dependencies = deps.map { it.id }
            )
        }
        
        // Jednoduchý algoritmus pro vlny (waves) na základě závislostí
        val stateWithWaves = assignWaves(allTasks)
        
        // Zavedení WIP limitu pro vlny (maximálně 3 úkoly najednou v rámci jedné vlny)
        val finalTasks = applyWipLimit(stateWithWaves, wipLimit = 3)
        
        val readyIds = finalTasks.filter { it.readiness?.ready == true }.map { it.id }
        val blockedIds = finalTasks.filter { it.readiness?.ready == false }.map { it.id }

        return ProgramState(
            tasks = finalTasks,
            queue = readyIds,
            blocked = blockedIds
        )
    }

    private fun applyWipLimit(tasks: List<WorkItemState>, wipLimit: Int): List<WorkItemState> {
        val result = mutableListOf<WorkItemState>()
        val waves = tasks.groupBy { it.wave }.toSortedMap()
        
        var waveOffset = 0
        waves.forEach { (waveNum, waveTasks) ->
            waveTasks.chunked(wipLimit).forEachIndexed { index, chunk ->
                chunk.forEach { task ->
                    result.add(task.copy(wave = waveNum + waveOffset + index))
                }
            }
            waveOffset += (waveTasks.size - 1) / wipLimit
        }
        return result
    }

    private fun evaluateReadiness(item: WorkItem, deps: List<WorkItem>, workflow: WorkflowDefinition): ReadinessReport {
        val statusGroup = when {
            workflow.stateGroups.executionReadyStates.contains(item.status) -> "READY"
            workflow.stateGroups.draftStates.contains(item.status) -> "DRAFT"
            workflow.stateGroups.doingStates.contains(item.status) -> "DOING"
            workflow.stateGroups.reviewStates.contains(item.status) -> "REVIEW"
            workflow.stateGroups.blockedStates.contains(item.status) -> "BLOCKED"
            workflow.stateGroups.terminalStates.contains(item.status) -> "TERMINAL"
            else -> "UNKNOWN"
        }

        val blockingDeps = deps.filter { dep -> 
            // Závislost je blokující, pokud není v terminálním stavu
            // Poznámka: Zde bychom potřebovali i workflow závislosti, zjednodušujeme
            dep.status != "Done" 
        }

        val missingReqs = mutableListOf<String>()
        if (statusGroup != "READY") {
            missingReqs.add("Item is in state '${item.status}', which is not execution-ready.")
        }
        if (blockingDeps.isNotEmpty()) {
            missingReqs.add("Blocked by: ${blockingDeps.joinToString { it.id }}")
        }

        return ReadinessReport(
            ready = statusGroup == "READY" && blockingDeps.isEmpty(),
            statusGroup = statusGroup,
            missingRequirements = missingReqs,
            blockingRelations = blockingDeps.map { it.id },
            commentDraft = if (statusGroup != "READY") "JERVIS: This item is not ready for execution because it is in state '${item.status}'." else null
        )
    }

    private fun assignWaves(tasks: List<WorkItemState>): List<WorkItemState> {
        val result = tasks.toMutableList()
        val taskIdToState = tasks.associateBy { it.id }.toMutableMap()
        val visited = mutableSetOf<String>()
        val currentPath = mutableSetOf<String>()

        fun calculateWave(taskId: String): Int {
            if (currentPath.contains(taskId)) return 0 // Cycle detected, fallback
            val state = taskIdToState[taskId] ?: return 0
            
            // Pro zjednodušení nepoužíváme memoizaci wave, abychom se vyhnuli problémům s mutable listem
            currentPath.add(taskId)
            val maxDepWave = state.dependencies.map { calculateWave(it) }.maxOrNull() ?: -1
            currentPath.remove(taskId)
            
            return maxDepWave + 1
        }

        return tasks.map { it.copy(wave = calculateWave(it.id)) }
    }
}
