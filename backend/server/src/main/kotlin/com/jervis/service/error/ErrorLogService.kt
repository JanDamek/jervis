package com.jervis.service.error

import com.jervis.domain.error.ErrorLog
import com.jervis.entity.ErrorLogDocument
import com.jervis.repository.ErrorLogMongoRepository
import com.jervis.service.notification.ErrorNotificationsPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.io.PrintWriter
import java.io.StringWriter

@Service
class ErrorLogService(
    private val repository: ErrorLogMongoRepository,
    private val errorPublisher: ErrorNotificationsPublisher,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun recordError(
        throwable: Throwable,
        clientId: ObjectId? = null,
        projectId: ObjectId? = null,
        correlationId: String? = null,
    ): ErrorLog =
        withContext(Dispatchers.IO) {
            val stack = throwable.toStackTraceString()
            val domain =
                ErrorLog(
                    clientId = clientId,
                    projectId = projectId,
                    correlationId = correlationId,
                    message = throwable.message ?: throwable.javaClass.name,
                    stackTrace = stack,
                    causeType = throwable.javaClass.name,
                )
            val saved = repository.save(ErrorLogDocument.fromDomain(domain)).toDomain()

            // Publish over websocket for UI dialog
            errorPublisher.publishError(
                message = saved.message,
                stackTrace = saved.stackTrace,
                correlationId = saved.correlationId,
            )
            logger.error(throwable) { "Captured error persisted id=${saved.id}" }
            saved
        }

    suspend fun list(
        clientId: ObjectId,
        limit: Int,
    ): List<ErrorLog> =
        withContext(Dispatchers.IO) {
            repository
                .findAllByClientIdOrderByCreatedAtDesc(clientId, PageRequest.of(0, limit))
                .toList()
                .map { it.toDomain() }
        }

    suspend fun listAll(limit: Int): List<ErrorLog> =
        withContext(Dispatchers.IO) {
            repository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
                .toList()
                .map { it.toDomain() }
        }

    suspend fun get(id: ObjectId): ErrorLog =
        withContext(Dispatchers.IO) {
            repository.findById(id)?.toDomain()
                ?: throw NoSuchElementException("ErrorLog with id=$id not found")
        }

    suspend fun delete(id: ObjectId): Unit =
        withContext(Dispatchers.IO) {
            repository.deleteById(id)
        }

    suspend fun deleteAll(clientId: ObjectId): Long = withContext(Dispatchers.IO) { repository.deleteAllByClientId(clientId) }
}

private fun Throwable.toStackTraceString(): String =
    StringWriter()
        .also { sw ->
            PrintWriter(sw).use { pw -> this.printStackTrace(pw) }
        }.toString()
