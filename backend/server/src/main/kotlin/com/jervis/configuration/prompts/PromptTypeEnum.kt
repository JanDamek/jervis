package com.jervis.configuration.prompts

enum class PromptTypeEnum(
    val aliases: List<String>,
) {
    // KNOWLEDGE MANAGEMENT
    KNOWLEDGE_SEARCH_TOOL(listOf("knowledge_search", "rag.search", "search_knowledge")),
    KNOWLEDGE_STORE_TOOL(listOf("knowledge_store", "rag.store", "store_knowledge")),
    SOURCE_FETCH_ORIGINAL_TOOL(listOf("source_fetch_original", "fetch_original", "fetch_source")),

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
    PROJECT_GIT_SYNC_TOOL(listOf("project_git_sync", "git_sync", "sync_repo")),

    // GIT TOOLS (explicit access to Git data)
    GIT_COMMIT_DIFF_TOOL(listOf("git_commit_diff", "git_diff", "commit_diff")),
    GIT_FILE_CURRENT_CONTENT_TOOL(listOf("git_file_current_content", "git_file_content", "file_content")),
    GIT_COMMIT_FILES_LIST_TOOL(listOf("git_commit_files_list", "git_files", "commit_files")),

    // SYSTEM OPERATIONS
    SYSTEM_EXECUTE_COMMAND_TOOL(listOf("system_execute_command", "exec_command", "system.exec")),
    SYSTEM_SCHEDULE_TASK_TOOL(listOf("system_schedule_task", "schedule_task", "task.schedule")),
    CREATE_PENDING_TASK_TOOL(listOf("create_pending_task", "pending_task", "delegate_task")),
    CONSOLIDATE_STEPS_TOOL(listOf("consolidate_steps", "merge_steps", "steps.consolidate")),

    // USER TASK MANAGEMENT
    TASK_CREATE_USER_TASK_TOOL(listOf("task_create_user", "create_user_task", "add_task")),
    TASK_QUERY_USER_TASKS_TOOL(listOf("task_query_user", "query_tasks", "list_tasks")),

    // USER REQUIREMENT MANAGEMENT
    REQUIREMENT_CREATE_USER_TOOL(listOf("requirement_create_user", "create_requirement", "add_requirement")),
    REQUIREMENT_QUERY_USER_TOOL(listOf("requirement_query_user", "query_requirements", "list_requirements")),
    REQUIREMENT_UPDATE_USER_TOOL(listOf("requirement_update_user", "update_requirement", "complete_requirement")),

    // COMMUNICATION
    COMMUNICATION_EMAIL_TOOL(listOf("communication_email", "send_email", "email")),
    COMMUNICATION_TEAMS_TOOL(listOf("communication_teams", "send_teams", "teams")),
    COMMUNICATION_SLACK_TOOL(listOf("communication_slack", "send_slack", "slack")),
    COMMUNICATION_USER_DIALOG_TOOL(listOf("communication_user_dialog", "user_dialog", "ask_user")),

    // ANALYSIS & REASONING
    ANALYSIS_REASONING_TOOL(listOf("analysis_reasoning", "analysis", "reasoning")),

    // CONTENT PROCESSING
    CONTENT_SEARCH_WEB_TOOL(listOf("content_search_web", "search_web", "web.search")),

    // PLANNING & ORCHESTRATION
    PLANNING_CREATE_PLAN_TOOL(listOf("planning_create_plan", "create_plan", "plan.create")),
    PLANNING_ANALYZE_QUESTION(listOf("planning_analyze_question", "analyze_question", "plan.analyze")),
    FINALIZER_ANSWER(listOf("finalizer_answer", "final_answer", "answer.final")),

    // INTERNAL/ANALYSIS PROMPTS
    CLIENT_DESCRIPTION_SHORT(listOf("client_description_short")),
    CLIENT_DESCRIPTION_FULL(listOf("client_description_full")),
    MEETING_TRANSCRIPT_PROCESSING(listOf("meeting_transcript_processing")),
    GIT_COMMIT_PROCESSING(listOf("git_commit_processing")),
    CLASS_SUMMARY(listOf("class_summary")),

    // QUALIFIER PROMPTS (small model pre-filtering)
    EMAIL_QUALIFIER(listOf("email_qualifier", "qualify_email", "email.qualify")),
    LINK_QUALIFIER(listOf("link_qualifier", "qualify_link", "link.qualify")),
    ;

    companion object {
        fun fromString(input: String): PromptTypeEnum? =
            entries.find { type ->
                type.aliases.any { it.equals(input, ignoreCase = true) } ||
                    type.name.equals(input, ignoreCase = true)
            }
    }
}
