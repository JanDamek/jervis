package com.jervis.domain.rag

/**
 * Type classification for knowledge fragments in the Knowledge Engine.
 *
 * - RULE: Normative mandates that must/should be followed (e.g., "All DTOs must be immutable")
 * - MEMORY: Contextual facts, decisions, preferences (e.g., "We use Keycloak for auth")
 */
enum class KnowledgeType {
    /** Normative rules and constraints - used in Planner as CONSTRAINTS */
    RULE,

    /** Contextual information and facts - used in Planner as CONTEXT */
    MEMORY,
}
