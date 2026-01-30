package com.jervis.orchestrator.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("NormalizedRequest")
@LLMDescription("Normalized user request with classified type, referenced entities, desired outcome, and validation checklist. Created by InterpreterAgent from raw user input.")
data class NormalizedRequest(
    @property:LLMDescription("Request type: ADVICE (research/info), CODE_ANALYSIS (read-only exploration), MESSAGE_DRAFT, CODE_CHANGE (modification), EPIC (large implementation), or BACKLOG_PROGRAM (program design)")
    val type: RequestType,

    @property:LLMDescription("Entities referenced in request (JIRA tickets, Confluence pages, files, etc.)")
    val entities: List<EntityRef> = emptyList(),

    @property:LLMDescription("Desired outcome or deliverable - what success looks like (e.g., 'Find all NTB purchases from Alza')")
    val outcome: String,

    @property:LLMDescription("Validation checklist - steps to verify request is fully satisfied and minimize risks")
    val checklist: List<String> = emptyList(),

    @property:LLMDescription("Target audience for MESSAGE_DRAFT requests (e.g., 'technical team', 'stakeholders', 'end users')")
    val targetAudience: String? = null,

    @property:LLMDescription("Desired tone for MESSAGE_DRAFT requests (e.g., 'formal', 'casual', 'technical', 'friendly')")
    val tone: String? = null
)

@Serializable
@SerialName("RequestType")
enum class RequestType {
    ADVICE,              // General questions, research, historical data
    CODE_ANALYSIS,       // Code structure analysis, "find where", "show me" - uses RAG/GraphDB/Joern
    MESSAGE_DRAFT,       // Draft emails, messages
    CODE_CHANGE,         // Actual code modification - requires credit check
    EPIC,               // Epic implementation
    BACKLOG_PROGRAM      // Program design
}

@Serializable
@SerialName("EntityRef")
@LLMDescription("Reference to external entity (JIRA ticket, Confluence page, file, etc.)")
data class EntityRef(
    @property:LLMDescription("Entity identifier (e.g., 'PROJ-123', 'page-456', '/path/to/file.kt')")
    val id: String,

    @property:LLMDescription("Entity type (e.g., 'jira_ticket', 'confluence_page', 'file', 'email')")
    val type: String,

    @property:LLMDescription("Source system (e.g., 'jira', 'confluence', 'filesystem', 'email')")
    val source: String
)
