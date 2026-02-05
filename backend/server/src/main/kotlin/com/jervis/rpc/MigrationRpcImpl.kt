package com.jervis.rpc

import com.jervis.service.migration.ConnectionCapabilityMigrationService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * REST endpoints for database migrations.
 *
 * These are administrative endpoints that should only be called
 * during maintenance windows or deployment.
 */
fun Route.migrationRoutes(migrationService: ConnectionCapabilityMigrationService) {
    route("/api/migrate") {
        /**
         * Migrate project legacy connection fields to connectionCapabilities.
         *
         * This migration is idempotent - safe to run multiple times.
         *
         * POST /api/migrate/project-capabilities
         */
        post("/project-capabilities") {
            logger.info { "Starting project capabilities migration via REST endpoint" }

            try {
                val result = migrationService.migrateAll()

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "status" to "completed",
                        "totalProjects" to result.totalProjects,
                        "migrated" to result.migrated,
                        "skipped" to result.skipped,
                        "errors" to result.errors,
                    ),
                )
            } catch (e: Exception) {
                logger.error(e) { "Migration failed" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "status" to "failed",
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
            }
        }

        /**
         * Check migration status - dry run without making changes.
         *
         * GET /api/migrate/project-capabilities/status
         */
        get("/project-capabilities/status") {
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "available",
                    "description" to "Migration endpoint for converting legacy project connection fields to connectionCapabilities",
                    "endpoint" to "POST /api/migrate/project-capabilities",
                    "idempotent" to true,
                ),
            )
        }
    }
}
