package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Tracks incremental project changes for analysis.
 * Each entry represents one commit analyzed for project evolution context.
 *
 * Purpose:
 * - Build understanding of how project evolved over time
 * - Provide context for agents analyzing current state
 * - Track what changed and why in natural language
 *
 * One commit = one evolution entry.
 * Order field maintains chronology (handles merge commits, rebases correctly).
 */
@Document(collection = "project_evolution")
@CompoundIndexes(
    CompoundIndex(name = "project_order_idx", def = "{'projectId': 1, 'order': 1}"),
    CompoundIndex(name = "project_commit_idx", def = "{'projectId': 1, 'commitHash': 1}"),
)
data class ProjectEvolutionDocument(
    @Id
    val id: ObjectId = ObjectId(),
    val projectId: ObjectId,
    /** Single commit hash for this evolution entry */
    val commitHash: String,
    /** Short summary of what changed (1-2 sentences) */
    val summary: String,
    /**
     * Full LLM analysis of the change:
     * - What changed (files, classes, features)
     * - Why it changed (purpose, problem solved)
     * - How it affects project (architecture impact, new capabilities)
     * - Related requirements or tasks
     */
    val analysis: String,
    /**
     * Chronological order of this change.
     * Important: Git commit order can be complex (merges, rebases).
     * Use this field for timeline, not timestamp.
     */
    val order: Int,
)
