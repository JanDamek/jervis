package com.jervis.dto.agentjob

/**
 * Kind of work an AgentJobRecord represents.
 *
 * Each flavor pins down the Job's resource profile and the handler that
 * decides how to render its workspace / CLAUDE.md brief — coding needs
 * a git checkout, research does not, scheduled_* flavors are cron
 * invocations of otherwise ordinary flavors.
 *
 * New flavors are added by extending this enum AND registering a
 * handler in the Kotlin agent-job dispatcher; the orchestrator never
 * interprets the value as an opaque string.
 */
enum class AgentJobFlavor {
    /** Implementation work on a project repository. Server prepares the
     * per-project workspace (clone / fetch / checkout branch), Job does
     * commit + push on that branch. Never opens a PR. */
    CODING,

    /** Deep analytical pass over supplied context (documents, KB
     * extracts, budget math). Read-only, no workspace. */
    ANALYSIS,

    /** Cross-source investigation (KB + web + O365 + scratchpad).
     * Read-only, broad tool access. */
    RESEARCH,

    /** Real-time meeting assistant — live SSE from transcription, Claude
     * suggests and answers during the meeting. Subset of existing
     * companion session flow. */
    MEETING_ATTENDANT,

    /** User-replacing meeting attendee — Claude joins the call through
     * a browser pod, listens, speaks, answers. Separate track. */
    MEETING_SUBSTITUTE,

    /** Scheduled recurring briefing / report / check. Description
     * carries the intent; handler picks ad-hoc Claude Job. */
    SCHEDULED,
}
