package com.jervis.controller

import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.client.ClientService
import com.jervis.repository.mongo.ProjectMongoRepository
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ClientsController(
    private val clientService: ClientService,
    private val projectRepository: ProjectMongoRepository,
) {
    private val slugRegex = Regex("^[a-z0-9-]+$")

    // --- Clients CRUD ---
    @PostMapping("/clients")
    suspend fun createClient(@RequestBody body: ClientDocument): ResponseEntity<Any> {
        return try {
            if (!body.slug.matches(slugRegex)) {
                ResponseEntity.badRequest().body(mapOf("error" to "Invalid slug"))
            } else {
                ResponseEntity.status(HttpStatus.CREATED).body(clientService.create(body))
            }
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "Invalid client")))
        }
    }

    @GetMapping("/clients")
    suspend fun listClients(): List<ClientDocument> = clientService.list()

    @GetMapping("/clients/{id}")
    suspend fun getClient(@PathVariable id: String): ResponseEntity<Any> = try {
        val oid = ObjectId(id)
        val c = clientService.get(oid) ?: return ResponseEntity.notFound().build()
        ResponseEntity.ok(c)
    } catch (_: IllegalArgumentException) {
        ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
    }

    @PutMapping("/clients/{id}")
    suspend fun updateClient(@PathVariable id: String, @RequestBody body: ClientDocument): ResponseEntity<Any> {
        return try {
            if (!body.slug.matches(slugRegex)) {
                ResponseEntity.badRequest().body(mapOf("error" to "Invalid slug"))
            } else {
                val oid = ObjectId(id)
                ResponseEntity.ok(clientService.update(oid, body))
            }
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "Invalid client")))
        }
    }

    @DeleteMapping("/clients/{id}")
    suspend fun deleteClient(@PathVariable id: String): ResponseEntity<Any> = try {
        val oid = ObjectId(id)
        clientService.delete(oid)
        ResponseEntity.noContent().build()
    } catch (_: IllegalArgumentException) {
        ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
    }

    // --- Nested Projects ---
    @PostMapping("/clients/{id}/projects")
    suspend fun createProjectForClient(
        @PathVariable id: String,
        @RequestBody body: ProjectDocument,
    ): ResponseEntity<Any> = try {
        val oid = ObjectId(id)
        validateProject(body)
        val toSave = body.copy(clientId = oid, updatedAt = java.time.Instant.now())
        val saved = projectRepository.save(toSave)
        ResponseEntity.status(HttpStatus.CREATED).body(saved)
    } catch (_: IllegalArgumentException) {
        ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
    } catch (e: Exception) {
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "Invalid project")))
    }

    @GetMapping("/clients/{id}/projects")
    suspend fun listProjectsForClient(@PathVariable id: String): ResponseEntity<Any> = try {
        val oid = ObjectId(id)
        val flow = projectRepository.findByClientId(oid)
        ResponseEntity.ok(flow.toList())
    } catch (_: IllegalArgumentException) {
        ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
    }

    // --- Test Integrations ---
    @PostMapping("/clients/{id}/test/integrations")
    suspend fun testIntegrations(@PathVariable id: String): ResponseEntity<Any> = try {
        val oid = ObjectId(id)
        val client = clientService.get(oid) ?: return ResponseEntity.notFound().build()
        val tools = client.tools
        val status = mapOf(
            "git" to (tools.git != null),
            "jira" to (tools.jira != null),
            "slack" to (tools.slack != null),
            "teams" to (tools.teams != null),
            "email" to (tools.email != null),
        )
        ResponseEntity.ok(mapOf("clientId" to client.id.toString(), "status" to status))
    } catch (_: IllegalArgumentException) {
        ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
    }

    private fun validateProject(p: ProjectDocument) {
        val slugRegex = Regex("^[a-z0-9-]+$")
        require(p.slug.matches(slugRegex)) { "Invalid slug: must match [a-z0-9-]+" }
        require(p.repo.primaryUrl.isNotBlank()) { "repo.primaryUrl is required" }
        if (p.inspirationOnly) {
            val anonymEnabled = p.overrides.anonymization?.enabled == true
            require(anonymEnabled) { "For inspirationOnly=true, overrides.anonymization.enabled must be true" }
        }
    }
}
