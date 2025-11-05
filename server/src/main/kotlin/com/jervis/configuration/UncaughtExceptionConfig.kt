package com.jervis.configuration

import com.jervis.service.error.ErrorLogService
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.context.annotation.Configuration

@Configuration
class UncaughtExceptionConfig(
    private val errorLogService: ErrorLogService,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun registerHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            logger.error(e) { "Uncaught exception in thread ${t.name}" }
            // Persist asynchronously to avoid blocking critical crash paths
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                errorLogService.recordError(e)
            }
            previous?.uncaughtException(t, e)
        }
        logger.info { "Global default UncaughtExceptionHandler registered" }
    }
}
