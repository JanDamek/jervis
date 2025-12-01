package com.jervis.configuration

import com.jervis.service.error.ErrorLogService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.context.annotation.Configuration

@Configuration
class UncaughtExceptionConfig(
    private val errorLogService: ErrorLogService,
) {
    private val logger = KotlinLogging.logger {}
    private val exceptionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PostConstruct
    fun registerHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            logger.error(e) { "Uncaught exception in thread ${t.name}" }
            exceptionScope.launch {
                errorLogService.recordError(e)
            }
            previous?.uncaughtException(t, e)
        }
        logger.info { "Global default UncaughtExceptionHandler registered" }
    }
}
