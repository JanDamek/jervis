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
 * Source code content for a single file (for tree-sitter parsing in KB service).
 */
@Serializable
data class GitFileContent(
    val path: String,
    val content: String,
)

/**
 * Request for direct structural ingest of git repository.
 *
 * Bypasses LLM entity extraction — creates graph nodes directly
 * from structured repository data (files, branches, classes).
 *
 * fileContents: Optional source code content for tree-sitter parsing.
 * When provided, KB service invokes tree-sitter to extract classes, methods,
 * and imports. Limited to top 150 source files, max 50KB per file.
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
    val fileContents: List<GitFileContent> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)
