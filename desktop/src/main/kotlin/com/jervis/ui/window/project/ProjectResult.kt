package com.jervis.ui.window.project

import com.jervis.dto.ProjectOverridesDto

/**
 * Result data class for project dialog
 */
data class ProjectResult(
    val name: String,
    val description: String,
    val projectPath: String?,
    val languages: List<String>,
    val inspirationOnly: Boolean,
    val includeGlobs: List<String>,
    val excludeGlobs: List<String>,
    val maxFileSizeMB: Int,
    val isDisabled: Boolean,
    val isDefault: Boolean,
    val overrides: ProjectOverridesDto,
)
