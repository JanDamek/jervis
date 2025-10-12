package com.jervis.configuration.prompts

enum class PromptTypeEnum(
    val aliases: List<String>,
) {
    // KNOWLEDGE MANAGEMENT
    KNOWLEDGE_SEARCH_TOOL(listOf("knowledge_search", "rag.search", "search_knowledge")),
    KNOWLEDGE_STORE_TOOL(listOf("knowledge_store", "rag.store", "store_knowledge")),
    KNOWLEDGE_FINALIZE_ANSWER(listOf("knowledge_finalize_answer")),

    // DOCUMENT PROCESSING
    DOCUMENT_EXTRACT_TEXT_TOOL(listOf("document_extract_text", "doc.extract", "extract_document")),
    DOCUMENT_FROM_WEB_TOOL(listOf("document_from_web", "doc.from_web", "extract_webpage")),

    // CODE ANALYSIS & MODIFICATION
    CODE_MODIFY_TOOL(listOf("code_modify", "modify_code", "patch_code")),
    CODE_ANALYZE_TOOL(listOf("code_analyze", "analyze_code", "code.analysis")),

    // PROJECT MANAGEMENT
    PROJECT_EXPLORE_STRUCTURE_TOOL(listOf("project_explore_structure", "search_codebase", "repo.tree")),
    PROJECT_REFRESH_INDEX_TOOL(listOf("project_refresh_index", "refresh_index", "reindex_project")),
    PROJECT_VERSION_CONTROL_TOOL(listOf("project_version_control", "git_control", "git")),

    // SYSTEM OPERATIONS
    SYSTEM_EXECUTE_COMMAND_TOOL(listOf("system_execute_command", "exec_command", "system.exec")),
    SYSTEM_SCHEDULE_TASK_TOOL(listOf("system_schedule_task", "schedule_task", "task.schedule")),
    SYSTEM_VIEW_TASKS_TOOL(listOf("system_view_tasks", "view_tasks", "tasks.view")),

    // COMMUNICATION
    COMMUNICATION_EMAIL_TOOL(listOf("communication_email", "send_email", "email")),
    COMMUNICATION_TEAMS_TOOL(listOf("communication_teams", "send_teams", "teams")),
    COMMUNICATION_SLACK_TOOL(listOf("communication_slack", "send_slack", "slack")),
    COMMUNICATION_USER_DIALOG_TOOL(listOf("communication_user_dialog", "user_dialog", "ask_user")),

    // ANALYSIS & REASONING
    ANALYSIS_REASONING_TOOL(listOf("analysis_reasoning", "analysis", "reasoning")),
    TASK_RESOLUTION_CHECKER(listOf("task_resolution_checker", "task.checker", "verify_result")),

    // CONTENT PROCESSING
    CONTENT_SEARCH_WEB_TOOL(listOf("content_search_web", "search_web", "web.search")),
    CONTENT_SPLIT_SENTENCES(listOf("content_split_sentences", "split_sentences", "sentence_split")),

    // PLANNING & ORCHESTRATION
    PLANNING_CREATE_PLAN_TOOL(listOf("planning_create_plan", "create_plan", "plan.create")),
    PLANNING_ANALYZE_QUESTION(listOf("planning_analyze_question", "analyze_question", "plan.analyze")),
    FINALIZER_ANSWER(listOf("finalizer_answer", "final_answer", "answer.final")),

    // INTERNAL/ANALYSIS PROMPTS
    CLIENT_DESCRIPTION_SHORT(listOf("client_description_short")),
    CLIENT_DESCRIPTION_FULL(listOf("client_description_full")),
    COMPREHENSIVE_FILE_ANALYSIS(listOf("comprehensive_file_analysis")),
    MEETING_TRANSCRIPT_PROCESSING(listOf("meeting_transcript_processing")),
    GIT_COMMIT_PROCESSING(listOf("git_commit_processing")),
    DOCUMENTATION_PROCESSING(listOf("documentation_processing")),
    CLASS_SUMMARY(listOf("class_summary")),
    METHOD_SUMMARY(listOf("method_summary")),
    AGENT_TOOL_SUFFIX(listOf("agent_tool_suffix")),
    ;

    companion object {
        fun fromString(input: String): PromptTypeEnum? =
            entries.find { type ->
                type.aliases.any { it.equals(input, ignoreCase = true) } ||
                    type.name.equals(input, ignoreCase = true)
            }
    }
}
