package com.jervis.projectgroup

import com.jervis.rpc.BaseRpcImpl
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.dto.project.ProjectGroupDto
import com.jervis.projectgroup.toDocument
import com.jervis.projectgroup.toDto
import com.jervis.infrastructure.error.ErrorLogService
import com.jervis.projectgroup.ProjectGroupService
import com.jervis.service.projectgroup.IProjectGroupService
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
