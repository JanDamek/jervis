package com.jervis.domain.rag

/**
 * Types of sources for documents in the unified RAG architecture.
 */
enum class RagSourceType {
    /** Joern-based code analysis (primary for supported languages) */
    JOERN,

    /** Fallback for code files not supported by Joern */
    CODE_FALLBACK,

    /** General text content (configs, documentation chunks) */
    TEXT_CONTENT,

    /** Documentation files and API docs */
    DOCUMENTATION,

    /** Git history and commit information */
    GIT_HISTORY,

    /** Meeting transcripts and recorded sessions */
    MEETING_TRANSCRIPT,

    /** Audio transcripts converted from speech-to-text */
    AUDIO_TRANSCRIPT,

    /** LLM-generated content */
    LLM,

    /** File-based content (legacy) */
    FILE,

    /** Analysis results */
    ANALYSIS,

    /** Agent-generated content */
    AGENT,

    /** External URL content */
    URL,

    /** Memory/context content */
    MEMORY,

    /** Email messages */
    EMAIL,

    /** Slack messages */
    SLACK,

    /** Microsoft Teams messages */
    TEAMS,

    /** Discord messages */
    DISCORD,

    /** Jira issues and comments */
    JIRA,
}
