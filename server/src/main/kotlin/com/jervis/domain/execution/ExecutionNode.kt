package com.jervis.domain.execution

import com.jervis.domain.plan.StepStatusEnum
import com.jervis.service.mcp.domain.ToolResult
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Base class for execution tree nodes that can be arranged in a dependency tree
 * supporting parallel execution and context merging.
 */
sealed class ExecutionNode {
    abstract val id: ObjectId
    abstract val planId: ObjectId
    abstract val contextId: ObjectId
    abstract var status: StepStatusEnum
    abstract var output: ToolResult?
    abstract val createdAt: Instant
    abstract var updatedAt: Instant

    /**
     * Returns all child nodes for tree traversal
     */
    abstract fun getChildren(): List<ExecutionNode>

    /**
     * Returns true if this node can be executed (all dependencies satisfied)
     */
    abstract fun canExecute(): Boolean

    /**
     * Returns true if this node and all its children are completed
     */
    abstract fun isCompleted(): Boolean
}

/**
 * A single executable task step that calls an MCP tool
 */
data class TaskStep(
    override val id: ObjectId = ObjectId.get(),
    override val planId: ObjectId,
    override val contextId: ObjectId,
    val name: String, // MCP tool name
    val taskDescription: String,
    val order: Int = -1, // For backward compatibility and UI display
    override var status: StepStatusEnum = StepStatusEnum.PENDING,
    override var output: ToolResult? = null,
    override val createdAt: Instant = Instant.now(),
    override var updatedAt: Instant = Instant.now(),
    // Dependencies - this step waits for these nodes to complete
    val dependsOn: List<ObjectId> = emptyList(),
) : ExecutionNode() {
    override fun getChildren(): List<ExecutionNode> = emptyList()

    override fun canExecute(): Boolean = status == StepStatusEnum.PENDING

    override fun isCompleted(): Boolean = status == StepStatusEnum.DONE || status == StepStatusEnum.FAILED
}

/**
 * A group of nodes that can execute in parallel
 */
data class ParallelGroup(
    override val id: ObjectId = ObjectId.get(),
    override val planId: ObjectId,
    override val contextId: ObjectId,
    val name: String, // Group description for logging
    val nodes: List<ExecutionNode>,
    override var status: StepStatusEnum = StepStatusEnum.PENDING,
    override var output: ToolResult? = null,
    override val createdAt: Instant = Instant.now(),
    override var updatedAt: Instant = Instant.now(),
) : ExecutionNode() {
    override fun getChildren(): List<ExecutionNode> = nodes

    override fun canExecute(): Boolean = status == StepStatusEnum.PENDING && nodes.any { it.canExecute() }

    override fun isCompleted(): Boolean = nodes.all { it.isCompleted() }
}

/**
 * A merge node that combines outputs from multiple parent nodes
 * and provides the combined context to dependent nodes
 */
data class MergeNode(
    override val id: ObjectId = ObjectId.get(),
    override val planId: ObjectId,
    override val contextId: ObjectId,
    val name: String = "Context Merge",
    val taskDescription: String = "Merge results from parallel execution branches",
    // Input nodes whose outputs will be merged
    val inputNodes: List<ObjectId>,
    // Child nodes that depend on this merge
    val childNodes: List<ExecutionNode> = emptyList(),
    override var status: StepStatusEnum = StepStatusEnum.PENDING,
    override var output: ToolResult? = null,
    override val createdAt: Instant = Instant.now(),
    override var updatedAt: Instant = Instant.now(),
) : ExecutionNode() {
    override fun getChildren(): List<ExecutionNode> = childNodes

    override fun canExecute(): Boolean =
        status == StepStatusEnum.PENDING &&
            // Can execute when all input nodes are completed
            inputNodes.isNotEmpty() // Will be validated against actual tree at runtime

    override fun isCompleted(): Boolean = status == StepStatusEnum.DONE || status == StepStatusEnum.FAILED
}

/**
 * A sequential group of nodes that execute one after another
 */
data class SequentialGroup(
    override val id: ObjectId = ObjectId.get(),
    override val planId: ObjectId,
    override val contextId: ObjectId,
    val name: String,
    val nodes: List<ExecutionNode>,
    override var status: StepStatusEnum = StepStatusEnum.PENDING,
    override var output: ToolResult? = null,
    override val createdAt: Instant = Instant.now(),
    override var updatedAt: Instant = Instant.now(),
) : ExecutionNode() {
    override fun getChildren(): List<ExecutionNode> = nodes

    override fun canExecute(): Boolean = status == StepStatusEnum.PENDING && nodes.firstOrNull()?.canExecute() == true

    override fun isCompleted(): Boolean = nodes.all { it.isCompleted() }
}

/**
 * A planning node that can dynamically expand the tree by creating new plans
 * based on information gathered from previous steps
 */
data class PlanningNode(
    override val id: ObjectId = ObjectId.get(),
    override val planId: ObjectId,
    override val contextId: ObjectId,
    val name: String = "Dynamic Planning",
    val taskDescription: String,
    // Input from previous steps that informs the planning
    val dependsOn: List<ObjectId> = emptyList(),
    // Nodes that will be created after planning completes
    var plannedNodes: List<ExecutionNode> = emptyList(),
    override var status: StepStatusEnum = StepStatusEnum.PENDING,
    override var output: ToolResult? = null,
    override val createdAt: Instant = Instant.now(),
    override var updatedAt: Instant = Instant.now(),
) : ExecutionNode() {
    override fun getChildren(): List<ExecutionNode> = plannedNodes

    override fun canExecute(): Boolean = status == StepStatusEnum.PENDING && dependsOn.isNotEmpty()

    override fun isCompleted(): Boolean = status == StepStatusEnum.DONE && plannedNodes.all { it.isCompleted() }
}
