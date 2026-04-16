package com.jervis.meeting

import com.jervis.infrastructure.storage.DirectoryStructureService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import kotlinx.coroutines.reactor.awaitSingleOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Nightly WebM retention cleanup (product §10a).
 *
 * Runs once every 24 h. For each MeetingDocument with
 * `videoRetentionUntil != null` AND `videoRetentionUntil < now`, deletes the
 * `webmPath` file and the per-meeting chunk directory, then clears both
 * fields on the document. The transcript + speaker embeddings + timeline
 * are intentionally kept forever — only the raw WebM is subject to
 * retention.
 */
@Component
class MeetingVideoRetentionJob(
    private val directoryStructureService: DirectoryStructureService,
    private val mongoTemplate: ReactiveMongoTemplate,
    private val meetingRepository: MeetingRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @PostConstruct
    fun start() {
        scope.launch {
            delay(60_000L)  // Let the app finish starting up.
            while (true) {
                runCatching { tick() }
                    .onFailure { logger.warn(it) { "retention tick failed" } }
                delay(INTERVAL_MS)
            }
        }
    }

    private suspend fun tick() {
        val now = Instant.now()
        val query = Query(
            Criteria.where("videoRetentionUntil").exists(true).lt(now)
                .and("webmPath").exists(true).ne(null),
        )
        val candidates = mongoTemplate
            .find(query, MeetingDocument::class.java)
            .collectList()
            .awaitSingleOrNull()
            ?: return
        for (meeting in candidates) {
            val removed = purgeMeetingVideo(meeting)
            if (removed) {
                meetingRepository.save(
                    meeting.copy(
                        webmPath = null,
                        videoRetentionUntil = null,
                    ),
                )
                logger.info {
                    "MEETING_VIDEO_PURGED | meeting=${meeting.id.toHexString()}"
                }
            }
        }
    }

    private fun purgeMeetingVideo(meeting: MeetingDocument): Boolean {
        var ok = true
        meeting.webmPath?.let { path ->
            runCatching { Files.deleteIfExists(Path.of(path)) }
                .onFailure { logger.warn(it) { "delete webm failed $path" }; ok = false }
        }
        val chunkDir = runCatching {
            directoryStructureService.meetingVideoChunkDir(
                meeting.id, meeting.clientId, meeting.projectId,
            )
        }.getOrNull()
        if (chunkDir != null && Files.exists(chunkDir)) {
            runCatching {
                Files.walk(chunkDir).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { p ->
                        Files.deleteIfExists(p)
                    }
                }
            }.onFailure { logger.warn(it) { "delete chunk dir failed $chunkDir" }; ok = false }
        }
        return ok
    }

    companion object {
        private val INTERVAL_MS = Duration.ofHours(24).toMillis()
    }
}
