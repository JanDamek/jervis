package com.jervis.controller

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.gitwatcher.GitClient
import com.jervis.service.indexer.IndexerService
import com.jervis.service.project.ProjectService
import kotlinx.coroutines.flow.firstOrNull
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

@RestController
@RequestMapping("/api")
class ProjectsController(
    private val projectRepository: ProjectMongoRepository,
    private val projectService: ProjectService,
    private val indexerService: IndexerService,
    private val gitClient: GitClient,
) {
    private val slugRegex = Regex("^[a-z0-9-]+$")

    // --- CRUD ---
    @GetMapping("/projects/{pid}")
    suspend fun getProject(@PathVariable pid: String): ResponseEntity<Any> {
        return try {
            val oid = ObjectId(pid)
            val proj = projectRepository.findById(oid.toString()) ?: return ResponseEntity.notFound().build()
            ResponseEntity.ok(proj)
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
        }
    }

    @PutMapping("/projects/{pid}")
    suspend fun updateProject(@PathVariable pid: String, @RequestBody body: ProjectDocument): ResponseEntity<Any> {
        return try {
            val oid = ObjectId(pid)
            validateProject(body)
            val existing = projectRepository.findById(oid.toString()) ?: return ResponseEntity.notFound().build()
            val merged = body.copy(id = existing.id, createdAt = existing.createdAt, clientId = existing.clientId, updatedAt = java.time.Instant.now())
            ResponseEntity.ok(projectRepository.save(merged))
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "Invalid project")))
        }
    }

    @DeleteMapping("/projects/{pid}")
    suspend fun deleteProject(@PathVariable pid: String): ResponseEntity<Any> {
        return try {
            val oid = ObjectId(pid)
            val proj = projectRepository.findById(oid.toString()) ?: return ResponseEntity.notFound().build()
            projectService.deleteProject(proj)
            ResponseEntity.noContent().build()
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
        }
    }

    // --- Indexing ---
    @PostMapping("/projects/{pid}/index/dry-run")
    suspend fun dryRunIndex(@PathVariable pid: String): ResponseEntity<Any> {
        return try {
            val oid = ObjectId(pid)
            val proj = projectRepository.findById(oid.toString()) ?: return ResponseEntity.notFound().build()
            val root = proj.path
            val dir = File(root)
            if (!dir.exists() || !dir.isDirectory) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "Project path not found: $root"))
            }
            var fileCount = 0
            var totalBytes = 0L
            Files.walk(Paths.get(root)).use { stream ->
                stream.forEach { p ->
                    if (Files.isRegularFile(p)) {
                        fileCount++
                        try { totalBytes += Files.size(p) } catch (_: Exception) {}
                    }
                }
            }
            ResponseEntity.ok(mapOf("projectId" to proj.id.toString(), "files" to fileCount, "bytes" to totalBytes))
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
        }
    }

    @PostMapping("/projects/{pid}/index/reindex")
    suspend fun reindex(@PathVariable pid: String): ResponseEntity<Any> {
        return try {
            val oid = ObjectId(pid)
            val proj = projectRepository.findById(oid.toString()) ?: return ResponseEntity.notFound().build()
            indexerService.indexProject(proj)
            ResponseEntity.ok(mapOf("status" to "started"))
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to (e.message ?: "Indexing failed")))
        }
    }

    // --- Tests ---
    @PostMapping("/projects/{pid}/test/repo")
    suspend fun testRepo(@PathVariable pid: String): ResponseEntity<Any> {
        return try {
            val oid = ObjectId(pid)
            val proj = projectRepository.findById(oid.toString()) ?: return ResponseEntity.notFound().build()
            val path = proj.path
            val dir = File(path)
            val exists = dir.exists() && dir.isDirectory
            val gitOk = if (exists) gitClient.getLastCommitInfo(path) != null else false
            ResponseEntity.ok(mapOf("pathExists" to exists, "gitDetected" to File(dir, ".git").exists(), "gitInfoAvailable" to gitOk))
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
        }
    }

    data class AnonTestRequest(val text: String)
    data class AnonTestResponse(val original: String, val anonymized: String)

    @PostMapping("/projects/{pid}/test/anonymization")
    suspend fun testAnonymization(@PathVariable pid: String, @RequestBody req: AnonTestRequest): ResponseEntity<Any> {
        return try {
            val oid = ObjectId(pid)
            val proj = projectRepository.findById(oid.toString()) ?: return ResponseEntity.notFound().build()
            val rules = proj.overrides.anonymization?.rules ?: emptyList()
            var result = req.text
            rules.forEach { rule ->
                val parts = rule.split("->", limit = 2)
                if (parts.size == 2) {
                    val pattern = parts[0].trim()
                    val replacement = parts[1].trim()
                    try {
                        result = result.replace(Regex(pattern), replacement)
                    } catch (_: Exception) { /* ignore bad regex */ }
                }
            }
            ResponseEntity.ok(AnonTestResponse(original = req.text, anonymized = result))
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid id"))
        }
    }

    private fun validateProject(p: ProjectDocument) {
        require(p.slug.matches(slugRegex)) { "Invalid slug: must match [a-z0-9-]+" }
        require(p.repo.primaryUrl.isNotBlank()) { "repo.primaryUrl is required" }
        if (p.inspirationOnly) {
            val anonymEnabled = p.overrides.anonymization?.enabled == true
            require(anonymEnabled) { "For inspirationOnly=true, overrides.anonymization.enabled must be true" }
        }
    }
}
