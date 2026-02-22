package com.jervis.rpc.internal

import com.jervis.rpc.MeetingRpcImpl
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import com.jervis.service.task.UserTaskService
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Internal REST endpoints for chat runtime context.
 *
 * Provides data that the Python chat handler injects into the LLM system prompt
 * so the model knows about available clients, pending tasks, and unclassified meetings.
 *
 * All endpoints return JSON and are called from Python orchestrator's kotlin_client.
 */
fun Routing.installInternalChatContextApi(
    clientService: ClientService,
    projectService: ProjectService,
    userTaskService: UserTaskService,
    meetingRpcImpl: MeetingRpcImpl,
) {
    // List clients with their projects (id, name) — for LLM scope resolution
    get("/internal/clients-projects") {
        try {
            val clients = clientService.list()
            val result = buildJsonArray {
                for (client in clients.filter { !it.archived }) {
                    val projects = projectService.listProjectsForClient(client.id)
                    add(buildJsonObject {
                        put("id", client.id.toString())
                        put("name", client.name)
                        put("projects", buildJsonArray {
                            for (p in projects) {
                                add(buildJsonObject {
                                    put("id", p.id.toString())
                                    put("name", p.name)
                                })
                            }
                        })
                    })
                }
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=clients-projects" }
            call.respondText("[]", ContentType.Application.Json)
        }
    }

    // Pending user tasks summary — count + top N for proactive mentions
    get("/internal/pending-user-tasks/summary") {
        try {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 3
            val result = userTaskService.findPagedTasks(
                query = null,
                offset = 0,
                limit = limit,
                stateFilter = com.jervis.dto.TaskStateEnum.USER_TASK,
            )
            val response = buildJsonObject {
                put("count", result.totalCount)
                put("tasks", buildJsonArray {
                    for (task in result.items) {
                        add(buildJsonObject {
                            put("id", task.id.toString())
                            put("title", task.taskName)
                            put("question", task.pendingUserQuestion ?: "")
                            put("clientId", task.clientId.toString())
                            put("projectId", task.projectId?.toString() ?: "")
                        })
                    }
                })
            }
            call.respondText(response.toString(), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=pending-user-tasks/summary" }
            call.respondText("""{"count":0,"tasks":[]}""", ContentType.Application.Json)
        }
    }

    // Count unclassified meetings — for proactive reminder in system prompt
    get("/internal/unclassified-meetings/count") {
        try {
            val meetings = meetingRpcImpl.listUnclassifiedMeetings()
            call.respondText(
                """{"count":${meetings.size}}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=unclassified-meetings/count" }
            call.respondText("""{"count":0}""", ContentType.Application.Json)
        }
    }
}
