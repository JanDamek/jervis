package com.jervis.project

import com.jervis.project.ProjectDocument

/**
 * Event published when a project needs workspace initialization.
 * BackgroundEngine listens to this event and triggers git clone.
 */
data class ProjectWorkspaceInitEvent(
    val project: ProjectDocument,
)
