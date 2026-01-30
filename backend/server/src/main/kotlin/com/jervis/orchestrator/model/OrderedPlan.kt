package com.jervis.orchestrator.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ordered execution plan created by PlannerAgent.
 *
 * No task IDs, no dependency tracking - order of steps IS the execution order.
 * Koog orchestrator executes steps sequentially.
 */
@Serializable
@SerialName("OrderedPlan")
@LLMDescription("Sequential execution plan with ordered steps and reasoning. Steps execute in list order - first to last.")
data class OrderedPlan(
    @property:LLMDescription("Steps to execute in sequential order. Each step is atomic and completes before next begins.")
    val steps: List<PlanStep>,

    @property:LLMDescription("Reasoning behind the plan design and step ordering (for debugging/logging)")
    val reasoning: String = "",
)

/**
 * Single atomic step in execution plan.
 * Order in list determines execution order (no IDs needed).
 */
@Serializable
@SerialName("PlanStep")
@LLMDescription("Single atomic step in execution plan with action category, executor hint, and detailed description")
data class PlanStep(
    @property:LLMDescription("Action category: searchKnowledgeBase, queryGraphDB, analyzeWithJoern, coding, verify, ragIngest, jiraUpdate, emailSend, askUser, etc.")
    val action: String,

    @property:LLMDescription("Executor hint: aider (fast surgical edits), openhands (complex debugging), internal (research/analysis), junie (AI development)")
    val executor: String,

    @property:LLMDescription("Clear description of what to do - be specific and include all necessary context (1-3 sentences)")
    val description: String,
)
