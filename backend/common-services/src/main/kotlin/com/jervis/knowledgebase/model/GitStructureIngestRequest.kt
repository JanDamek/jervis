package com.jervis.knowledgebase.model

import kotlinx.serialization.Serializable

/**
 * Metadata for a single file in a git repository.
 */
@Serializable
data class GitFileInfo(
    val path: String,
    val extension: String = "",
    val language: String = "",
    val sizeBytes: Long = 0,
)

/**
 * Metadata for a git branch.
 */
@Serializable
data class GitBranchInfo(
    val name: String,
    val isDefault: Boolean = false,
    val status: String = "active",
    val lastCommitHash: String = "",
)

/**
 * Class/type extracted from source code (e.g., via tree-sitter).
 */
@Serializable
data class GitClassInfo(
    val name: String,
    val qualifiedName: String = "",
    val filePath: String,
    val visibility: String = "public",
    val isInterface: Boolean = false,
    val methods: List<String> = emptyList(),
)

/**
 * Request for direct structural ingest of git repository.
 *
 * Bypasses LLM entity extraction — creates graph nodes directly
 * from structured repository data (files, branches, classes).
 */
@Serializable
data class GitStructureIngestRequest(
    val clientId: String,
    val projectId: String,
    val repositoryIdentifier: String,
    val branch: String,
    val defaultBranch: String = "main",
    val branches: List<GitBranchInfo> = emptyList(),
    val files: List<GitFileInfo> = emptyList(),
    val classes: List<GitClassInfo> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)
