package com.jervis.configuration.prompts

enum class ToolTypeEnum {
    // KNOWLEDGE MANAGEMENT
    KNOWLEDGE_SEARCH_TOOL,
    KNOWLEDGE_STORE_TOOL,
    SOURCE_FETCH_ORIGINAL_TOOL,

    // SYSTEM OPERATIONS (migrated to Koog SystemTools wrapper)
    SYSTEM_SCHEDULE_TASK_TOOL,
    SYSTEM_INDEX_LINK_TOOL,
    CREATE_PENDING_TASK_TOOL,

    // USER TASK MANAGEMENT (migrated to Koog TaskTools wrapper)
    TASK_CREATE_USER_TASK_TOOL,

    // USER REQUIREMENT MANAGEMENT
    REQUIREMENT_CREATE_USER_TOOL,
    REQUIREMENT_QUERY_USER_TOOL,
    REQUIREMENT_UPDATE_USER_TOOL,

    // COMMUNICATION
    COMMUNICATION_EMAIL_TOOL,
    COMMUNICATION_TEAMS_TOOL,
    COMMUNICATION_SLACK_TOOL,
    COMMUNICATION_USER_DIALOG_TOOL,

    // GRAPH & AGENTS
    // Note: Graph operations (upsert node/edge) are now handled by Koog GraphTools directly
    KOOG_AGENT_TOOL,

    // INTERNAL MARKERS (not callable tools, used for system operations)
    CONSOLIDATE_STEPS_TOOL, // Used by ContextCompactionService for marking consolidated steps
}
