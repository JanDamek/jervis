package com.jervis.controller.api

import com.jervis.dto.indexing.IndexingItemDto
import com.jervis.dto.indexing.IndexingOverviewDto
import com.jervis.dto.indexing.IndexingStateDto
import com.jervis.dto.indexing.IndexingToolDetailDto
import com.jervis.dto.indexing.IndexingToolSummaryDto
import com.jervis.service.IIndexingStatusService
import com.jervis.service.indexing.status.IndexingStatusRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/indexing/status")
class IndexingStatusRestController(
    private val registry: IndexingStatusRegistry,
) : IIndexingStatusService {
    private val fmt: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    @GetMapping
    override suspend fun getOverview(): IndexingOverviewDto {
        // Ensure Atlassian (Jira) tool is visible in overview even before first run
        registry.ensureTool(toolKey = IndexingStatusRegistry.ToolStateEnum.JIRA, displayName = "Atlassian (Jira)")
        // Ensure other indexing tools are visible as well
        registry.ensureTool(toolKey = IndexingStatusRegistry.ToolStateEnum.EMAIL, displayName = "Email Indexing")
        registry.ensureTool(toolKey = IndexingStatusRegistry.ToolStateEnum.GIT, displayName = "Git Indexing")
        registry.ensureTool(toolKey = IndexingStatusRegistry.ToolStateEnum.CONFLUENCE, displayName = "Atlassian (Confluence)")

        val tools =
            registry.snapshot().map { t ->
                val (indexedCount, newCount) =
                    kotlin
                        .runCatching { registry.getIndexedAndNewCounts(t.toolKey) }
                        .getOrDefault(0L to 0L)

                // idleReason removed - pollers run continuously via AbstractPeriodicPoller
                val idleReason: String? = null

                IndexingToolSummaryDto(
                    toolKey = t.toolKey.name,
                    displayName = t.displayName,
                    state = if (t.state == IndexingStatusRegistry.State.RUNNING) IndexingStateDto.RUNNING else IndexingStateDto.IDLE,
                    reason = t.reason,
                    idleReason = idleReason,
                    runningSince = t.runningSince?.let(fmt::format),
                    processed = t.processed,
                    errors = t.errors,
                    indexedCount = indexedCount,
                    newCount = newCount,
                    lastError = t.lastError,
                    lastErrorFull = t.lastErrorFull,
                    lastRunStartedAt = t.lastRunStartedAt?.let(fmt::format),
                    lastRunFinishedAt = t.lastRunFinishedAt?.let(fmt::format),
                )
            }
        return IndexingOverviewDto(tools = tools)
    }

    @GetMapping("/{toolKey}")
    override suspend fun getToolDetail(
        @PathVariable("toolKey") toolKey: String,
    ): IndexingToolDetailDto {
        val toolEnum = IndexingStatusRegistry.ToolStateEnum.valueOf(toolKey.uppercase())
        val t = registry.toolDetail(toolEnum) ?: IndexingStatusRegistry.ToolState(toolEnum, toolKey)
        val (indexedCount, newCount) =
            kotlin
                .runCatching { registry.getIndexedAndNewCounts(t.toolKey) }
                .getOrDefault(0L to 0L)

        // idleReason removed - pollers run continuously via AbstractPeriodicPoller
        val idleReason: String? = null

        val summary =
            IndexingToolSummaryDto(
                toolKey = t.toolKey.name,
                displayName = t.displayName,
                state = if (t.state == IndexingStatusRegistry.State.RUNNING) IndexingStateDto.RUNNING else IndexingStateDto.IDLE,
                reason = t.reason,
                idleReason = idleReason,
                runningSince = t.runningSince?.let(fmt::format),
                processed = t.processed,
                errors = t.errors,
                indexedCount = indexedCount,
                newCount = newCount,
                lastError = t.lastError,
                lastErrorFull = t.lastErrorFull,
                lastRunStartedAt = t.lastRunStartedAt?.let(fmt::format),
                lastRunFinishedAt = t.lastRunFinishedAt?.let(fmt::format),
            )
        val items =
            t.items.map { i ->
                IndexingItemDto(
                    timestamp = fmt.format(i.timestamp),
                    level = i.level,
                    message = i.message,
                    processedDelta = i.processedDelta,
                    errorsDelta = i.errorsDelta,
                    fullDetails = i.fullDetails,
                )
            }
        return IndexingToolDetailDto(summary = summary, items = items)
    }

    @PostMapping("/jira/run")
    override suspend fun runJiraNow() {
        // Pollers now run continuously - no manual trigger needed
        // Users can monitor progress via indexing status
    }

    @PostMapping("/email/run")
    override suspend fun runEmailNow() {
        // Pollers now run continuously - no manual trigger needed
    }

    @PostMapping("/git/run")
    override suspend fun runGitNow() {
        // Pollers now run continuously - no manual trigger needed
    }

    @PostMapping("/confluence/run")
    override suspend fun runConfluenceNow() {
        // Pollers now run continuously - no manual trigger needed
    }
}
