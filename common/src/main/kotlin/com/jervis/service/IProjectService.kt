package com.jervis.service

import com.jervis.entity.mongo.ProjectDocument

interface IProjectService {
    suspend fun getAllProjects(): List<ProjectDocument>

    suspend fun getDefaultProject(): ProjectDocument?

    suspend fun setActiveProject(project: ProjectDocument)

    suspend fun setDefaultProject(project: ProjectDocument)

    suspend fun saveProject(
        project: ProjectDocument,
        makeDefault: Boolean = false,
    ): ProjectDocument

    suspend fun deleteProject(project: ProjectDocument)

    suspend fun getProjectByName(name: String?): ProjectDocument
}
