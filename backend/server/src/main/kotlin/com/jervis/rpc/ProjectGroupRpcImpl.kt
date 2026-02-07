package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.dto.ProjectGroupDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IProjectGroupService
import com.jervis.service.error.ErrorLogService
import com.jervis.service.projectgroup.ProjectGroupService
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ProjectGroupRpcImpl(
    private val projectGroupService: ProjectGroupService,
    errorLogService: ErrorLogService,
) : BaseRpcImpl(errorLogService),
    IProjectGroupService {

    override suspend fun listGroupsForClient(clientId: String): List<ProjectGroupDto> =
        executeWithErrorHandling("listGroupsForClient") {
            projectGroupService.listGroupsForClient(ClientId(ObjectId(clientId))).map { it.toDto() }
        }

    override suspend fun getAllGroups(): List<ProjectGroupDto> =
        executeWithErrorHandling("getAllGroups") {
            projectGroupService.getAllGroups().map { it.toDto() }
        }

    override suspend fun getGroup(id: String): ProjectGroupDto =
        executeWithErrorHandling("getGroup") {
            projectGroupService.getGroupById(ProjectGroupId(ObjectId(id))).toDto()
        }

    override suspend fun saveGroup(group: ProjectGroupDto): ProjectGroupDto =
        executeWithErrorHandling("saveGroup") {
            projectGroupService.saveGroup(group.toDocument()).toDto()
        }

    override suspend fun updateGroup(id: String, group: ProjectGroupDto): ProjectGroupDto =
        executeWithErrorHandling("updateGroup") {
            projectGroupService.saveGroup(group.copy(id = id).toDocument()).toDto()
        }

    override suspend fun deleteGroup(id: String) {
        executeWithErrorHandling("deleteGroup") {
            projectGroupService.deleteGroup(ProjectGroupId(ObjectId(id)))
        }
    }
}
