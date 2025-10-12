package com.jervis.service

import com.jervis.entity.mongo.ProjectDocument

interface IIndexingService {
    suspend fun indexProject(project: ProjectDocument): Any

    suspend fun indexAllProjects(projects: List<ProjectDocument>)

    suspend fun indexProjectsForClient(
        projects: List<ProjectDocument>,
        clientName: String,
    )
}
