package com.jervis.controller.api

import com.jervis.service.background.PendingTaskService
import mu.KotlinLogging
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/**
 * REST API for PendingTask management.
 *
 * Note: Context has been removed - all data is now in content field.
 * Tasks are created with complete content, no need for manual updates.
 */
@RestController
@RequestMapping("/api/pending-tasks")
class PendingTaskRestController(
    private val pendingTaskService: PendingTaskService,
) {
    // No endpoints needed - tasks are created with complete content
}
