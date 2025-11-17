package com.jervis.configuration.prompts

enum class PromptTypeEnum(
    val aliases: List<String>,
    val capabilities: List<String> = emptyList(),
) {
    // KNOWLEDGE MANAGEMENT
    KNOWLEDGE_SEARCH_TOOL(
        listOf("knowledge_search", "rag.search", "search_knowledge"),
        listOf("rag-search", "knowledge-search", "index-search"),
    ),
    KNOWLEDGE_STORE_TOOL(listOf("knowledge_store", "rag.store", "store_knowledge"), listOf("knowledge-store", "store")),
    SOURCE_FETCH_ORIGINAL_TOOL(
        listOf("source_fetch_original", "fetch_original", "fetch_source"),
        listOf("fetch-original", "fetch-source", "read-email"),
    ),

    // DOCUMENT PROCESSING
    DOCUMENT_EXTRACT_TEXT_TOOL(
        listOf("document_extract_text", "doc.extract", "extract_document"),
        listOf("extract-document", "tika", "document-text"),
    ),
    DOCUMENT_FROM_WEB_TOOL(listOf("document_from_web", "doc.from_web", "extract_webpage"), listOf("web-extract", "extract-webpage")),

    // CODE ANALYSIS & MODIFICATION
    CODE_MODIFY_TOOL(listOf("code_modify", "modify_code", "patch_code"), listOf("code-modify", "apply-patch", "write-file")),
    CODE_ANALYZE_TOOL(
        listOf("code_analyze", "analyze_code", "code.analysis"),
        listOf("code-analysis", "call-graph", "dataflow", "security-analysis"),
    ),

    // PROJECT MANAGEMENT
    PROJECT_EXPLORE_STRUCTURE_TOOL(
        listOf("project_explore_structure", "search_codebase", "repo.tree"),
        listOf("project-structure", "list-files", "file-tree"),
    ),
    PROJECT_REFRESH_INDEX_TOOL(listOf("project_refresh_index", "refresh_index", "reindex_project"), listOf("reindex", "refresh-index")),
    PROJECT_VERSION_CONTROL_TOOL(listOf("project_version_control", "git_control", "git"), listOf("git", "version-control")),
    PROJECT_GIT_SYNC_TOOL(listOf("project_git_sync", "git_sync", "sync_repo"), listOf("git-sync", "git-pull")),

    // GIT TOOLS (explicit access to Git data)
    GIT_COMMIT_DIFF_TOOL(listOf("git_commit_diff", "git_diff", "commit_diff"), listOf("git-diff", "diff", "changes")),
    GIT_FILE_CURRENT_CONTENT_TOOL(listOf("git_file_current_content", "git_file_content", "file_content"), listOf("read-file", "git-file")),
    GIT_COMMIT_FILES_LIST_TOOL(listOf("git_commit_files_list", "git_files", "commit_files"), listOf("git-files-list", "changed-files")),

    // SYSTEM OPERATIONS
    SYSTEM_EXECUTE_COMMAND_TOOL(listOf("system_execute_command", "exec_command", "system.exec"), listOf("execute-shell", "run-command")),
    SYSTEM_SCHEDULE_TASK_TOOL(listOf("system_schedule_task", "schedule_task", "task.schedule"), listOf("schedule-task", "calendar")),
    SYSTEM_MANAGE_LINK_SAFETY_TOOL(listOf("system_manage_link_safety", "manage_link_safety", "link.safety"), listOf("link-safety", "banned-links", "allowed-links")),
    CREATE_PENDING_TASK_TOOL(listOf("create_pending_task", "pending_task", "delegate_task"), listOf("create-pending-task", "delegate")),
    CONSOLIDATE_STEPS_TOOL(listOf("consolidate_steps", "merge_steps", "steps.consolidate"), listOf("consolidate-steps")),

    // USER TASK MANAGEMENT
    TASK_CREATE_USER_TASK_TOOL(listOf("task_create_user", "create_user_task", "add_task"), listOf("create-user-task")),
    TASK_QUERY_USER_TASKS_TOOL(listOf("task_query_user", "query_tasks", "list_tasks"), listOf("query-user-tasks")),

    // USER REQUIREMENT MANAGEMENT
    REQUIREMENT_CREATE_USER_TOOL(listOf("requirement_create_user", "create_requirement", "add_requirement"), listOf("create-requirement")),
    REQUIREMENT_QUERY_USER_TOOL(listOf("requirement_query_user", "query_requirements", "list_requirements"), listOf("query-requirements")),
    REQUIREMENT_UPDATE_USER_TOOL(
        listOf("requirement_update_user", "update_requirement", "complete_requirement"),
        listOf("update-requirement"),
    ),

    // COMMUNICATION
    COMMUNICATION_EMAIL_TOOL(listOf("communication_email", "send_email", "email"), listOf("send-email", "email")),
    COMMUNICATION_TEAMS_TOOL(listOf("communication_teams", "send_teams", "teams"), listOf("send-teams", "teams")),
    COMMUNICATION_SLACK_TOOL(listOf("communication_slack", "send_slack", "slack"), listOf("send-slack", "slack")),
    COMMUNICATION_USER_DIALOG_TOOL(listOf("communication_user_dialog", "user_dialog", "ask_user"), listOf("user-dialog", "ask-user")),

    // ANALYSIS & REASONING
    ANALYSIS_REASONING_TOOL(listOf("analysis_reasoning", "analysis", "reasoning"), listOf("analysis", "synthesis", "reasoning")),

    // CONTENT PROCESSING
    CONTENT_SEARCH_WEB_TOOL(listOf("content_search_web", "search_web", "web.search"), listOf("web-search", "internet-search")),

    // PLANNING & ORCHESTRATION
    PLANNING_CREATE_PLAN_TOOL(listOf("planning_create_plan", "create_plan", "plan.create"), listOf("planner")),
    PLANNING_ANALYZE_QUESTION(listOf("planning_analyze_question", "analyze_question", "plan.analyze"), listOf("analyze-question")),
    PLANNER_TOOL_SELECTOR(listOf("planner_tool_selector", "tool_selector", "plan.tools"), listOf("tool-selector", "map-capability")),
    TOOL_REASONING(listOf("tool_reasoning", "reason_tools", "tool.reason"), listOf("tool-reasoning", "map-requirements-to-tools")),
    CONTEXT_COMPACTION(listOf("context_compaction", "compact_context", "context.compact"), listOf("context-compaction", "reduce-context")),
    FINALIZER_ANSWER(listOf("finalizer_answer", "final_answer", "answer.final"), listOf("finalize")),

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
        private fun normalize(value: String): String =
            value
                .trim()
                .lowercase()
                .replace("_", "-")
                .replace(" ", "-")
                .replace(Regex("-+"), "-")

        fun fromString(input: String): PromptTypeEnum? {
            val norm = normalize(input)
            return entries.find { type ->
                // match aliases
                type.aliases.any { normalize(it) == norm } ||
                    // match enum name
                    normalize(type.name) == norm
            }
        }

        fun matchByCapability(capability: String): PromptTypeEnum? {
            val norm = normalize(capability)
            return entries.find { type ->
                // match declared capabilities keywords
                type.capabilities.any { normalize(it) == norm } ||
                    // allow aliases to be used as capabilities too
                    type.aliases.any { normalize(it) == norm } ||
                    // as a last resort, allow matching enum name variations
                    normalize(type.name) == norm
            }
        }
    }
}
