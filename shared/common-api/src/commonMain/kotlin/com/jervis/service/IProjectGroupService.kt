package com.jervis.service

import com.jervis.dto.ProjectGroupDto
import kotlinx.rpc.annotations.Rpc

/**
 * Project Group Service API.
 * kotlinx-rpc will auto-generate client implementation for Desktop/Mobile.
 */
@Rpc
interface IProjectGroupService {
    suspend fun listGroupsForClient(clientId: String): List<ProjectGroupDto>

    suspend fun getAllGroups(): List<ProjectGroupDto>

    suspend fun getGroup(id: String): ProjectGroupDto

    suspend fun saveGroup(group: ProjectGroupDto): ProjectGroupDto

    suspend fun updateGroup(id: String, group: ProjectGroupDto): ProjectGroupDto

    suspend fun deleteGroup(id: String)
}
