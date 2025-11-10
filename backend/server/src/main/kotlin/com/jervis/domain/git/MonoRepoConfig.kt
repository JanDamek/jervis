package com.jervis.domain.git

/**
 * Configuration for a client's mono-repository.
 *
 * A mono-repository contains multiple projects in a single Git repository.
 * This configuration allows clients to define multiple mono-repos, each with
 * optional credential overrides.
 *
 * Mono-repos are indexed once at the client level (not per-project) to enable
 * cross-project code discovery via RAG.
 */
data class MonoRepoConfig(
    val id: String,
    val name: String,
    val repositoryUrl: String,
    val defaultBranch: String = "main",
    val credentialsOverride: GitConfig? = null,
)
