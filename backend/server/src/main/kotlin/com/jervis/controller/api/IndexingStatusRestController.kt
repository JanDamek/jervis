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
    private val jiraPollingScheduler: com.jervis.service.jira.JiraPollingScheduler,
) : IIndexingStatusService {
    private val fmt: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    @GetMapping
    override suspend fun getOverview(): IndexingOverviewDto {
        val tools =
            registry.snapshot().map { t ->
                IndexingToolSummaryDto(
                    toolKey = t.toolKey,
                    displayName = t.displayName,
                    state = if (t.state == IndexingStatusRegistry.State.RUNNING) IndexingStateDto.RUNNING else IndexingStateDto.IDLE,
                    reason = t.reason,
                    runningSince = t.runningSince?.let(fmt::format),
                    processed = t.processed,
                    errors = t.errors,
                    lastError = t.lastError,
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
        val t = registry.toolDetail(toolKey) ?: IndexingStatusRegistry.ToolState(toolKey, toolKey)
        val summary =
            IndexingToolSummaryDto(
                toolKey = t.toolKey,
                displayName = t.displayName,
                state = if (t.state == IndexingStatusRegistry.State.RUNNING) IndexingStateDto.RUNNING else IndexingStateDto.IDLE,
                reason = t.reason,
                runningSince = t.runningSince?.let(fmt::format),
                processed = t.processed,
                errors = t.errors,
                lastError = t.lastError,
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
                )
            }
        return IndexingToolDetailDto(summary = summary, items = items)
    }

    @PostMapping("/jira/run/{clientId}")
    override suspend fun runJiraNow(
        @PathVariable("clientId") clientId: String,
    ) {
        // Delegate to scheduler manual trigger
        jiraPollingScheduler.triggerManual(clientId)
    }
}
