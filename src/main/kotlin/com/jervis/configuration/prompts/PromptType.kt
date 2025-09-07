package com.jervis.configuration.prompts

enum class PromptType {
    // Agent System
    AGENT_TOOL_SUFFIX,
    PLANNER_SYSTEM,
    FINALIZER_SYSTEM,

    // Language Processing
    TRANSLATION_SYSTEM,

    // MCP Tools
    RAG_QUERY_SYSTEM,
    JOERN_SYSTEM,
    TERMINAL_SYSTEM,
    LLM_SYSTEM,
    USER_INTERACTION_SYSTEM,
    CODE_WRITE_SYSTEM,
    SCOPE_RESOLUTION_SYSTEM,

    // Indexing
    CLASS_SUMMARY_SYSTEM,
    DEPENDENCY_ANALYSIS_SYSTEM,
}
