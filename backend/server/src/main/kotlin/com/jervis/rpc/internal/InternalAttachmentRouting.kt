package com.jervis.rpc.internal

import com.jervis.email.EmailMessageIndexRepository
import com.jervis.infrastructure.storage.DirectoryStructureService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Internal REST API for attachment operations — used by Python orchestrator.
 *
 * GET /internal/attachments/email/{emailId}        — list attachments for an email
 * GET /internal/attachments/email/{emailId}/{index} — download attachment binary
 * GET /internal/attachments/download?path=...      — download by storage path
 */
fun Routing.installInternalAttachmentApi(
    emailRepository: EmailMessageIndexRepository,
    directoryStructureService: DirectoryStructureService,
) {
    /**
     * List attachments for an email.
     * Returns JSON array with filename, contentType, size, storagePath for each attachment.
     */
    get("/internal/attachments/email/{emailId}") {
        val emailId = call.parameters["emailId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing emailId")

        try {
            val email = emailRepository.findById(ObjectId(emailId))
                ?: return@get call.respond(HttpStatusCode.NotFound, "Email not found")

            val attachments = email.attachments.mapIndexed { index, att ->
                AttachmentInfoResponse(
                    index = index,
                    filename = att.filename,
                    contentType = att.contentType,
                    size = att.size,
                    storagePath = att.storagePath,
                    downloadUrl = if (att.storagePath != null) {
                        "/internal/attachments/email/$emailId/$index"
                    } else null,
                )
            }

            call.respondText(
                json.encodeToString(attachments),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list attachments for email=$emailId" }
            call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error")
        }
    }

    /**
     * Download a specific attachment by email ID and index.
     * Returns binary data with correct content-type.
     */
    get("/internal/attachments/email/{emailId}/{index}") {
        val emailId = call.parameters["emailId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing emailId")
        val index = call.parameters["index"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid index")

        try {
            val email = emailRepository.findById(ObjectId(emailId))
                ?: return@get call.respond(HttpStatusCode.NotFound, "Email not found")

            if (index < 0 || index >= email.attachments.size) {
                return@get call.respond(HttpStatusCode.NotFound, "Attachment index out of range")
            }

            val attachment = email.attachments[index]
            val storagePath = attachment.storagePath
                ?: return@get call.respond(HttpStatusCode.NotFound, "Attachment not stored on disk")

            val bytes = directoryStructureService.readKbDocument(storagePath)

            call.respondBytes(
                bytes = bytes,
                contentType = ContentType.parse(attachment.contentType),
            )
        } catch (e: IllegalStateException) {
            logger.warn { "Attachment file not found: ${e.message}" }
            call.respond(HttpStatusCode.NotFound, "Attachment file not found on disk")
        } catch (e: Exception) {
            logger.error(e) { "Failed to download attachment email=$emailId index=$index" }
            call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error")
        }
    }

    /**
     * Download attachment by direct storage path.
     * Used when orchestrator has the path from task metadata.
     */
    get("/internal/attachments/download") {
        val path = call.request.queryParameters["path"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing 'path' parameter")

        try {
            val bytes = directoryStructureService.readKbDocument(path)
            // Detect content type from extension
            val contentType = when {
                path.endsWith(".pdf", ignoreCase = true) -> ContentType.Application.Pdf
                path.endsWith(".xlsx", ignoreCase = true) -> ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                path.endsWith(".docx", ignoreCase = true) -> ContentType.parse("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                path.endsWith(".png", ignoreCase = true) -> ContentType.Image.PNG
                path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> ContentType.Image.JPEG
                else -> ContentType.Application.OctetStream
            }

            call.respondBytes(bytes = bytes, contentType = contentType)
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.NotFound, "File not found: $path")
        } catch (e: Exception) {
            logger.error(e) { "Failed to download attachment path=$path" }
            call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error")
        }
    }
}

@Serializable
data class AttachmentInfoResponse(
    val index: Int,
    val filename: String,
    val contentType: String,
    val size: Long,
    val storagePath: String?,
    val downloadUrl: String?,
)
