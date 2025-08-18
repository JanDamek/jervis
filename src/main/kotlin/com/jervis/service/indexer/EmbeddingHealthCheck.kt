package com.jervis.service.indexer

import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class EmbeddingHealthCheck(
    private val embeddingService: EmbeddingService
) {
    private val logger = KotlinLogging.logger {}
    
    private var consecutiveFailures = 0
    private var isHealthy = true
    private val maxConsecutiveFailures = 3
    
    @Scheduled(fixedDelay = 60000) // Check every minute
    fun checkEmbeddingHealth() {
        try {
            logger.debug { "Performing embedding health check..." }
            
            // Test text embedding using runBlocking to handle suspend functions
            val textHealthCheck = kotlinx.coroutines.runBlocking {
                performTextEmbeddingHealthCheck()
            }
            
            // Test code embedding
            val codeHealthCheck = kotlinx.coroutines.runBlocking {
                performCodeEmbeddingHealthCheck()
            }
            
            if (textHealthCheck && codeHealthCheck) {
                if (!isHealthy) {
                    logger.info { "Embedding services recovered - marking as healthy" }
                    isHealthy = true
                }
                consecutiveFailures = 0
                logger.debug { "Embedding health check passed" }
            } else {
                handleHealthCheckFailure()
            }
            
        } catch (e: OutOfMemoryError) {
            logger.error { "OOM in embedding service - attempting recovery" }
            handleOutOfMemoryError()
        } catch (e: Exception) {
            logger.error(e) { "Embedding health check failed: ${e.message}" }
            handleHealthCheckFailure()
        }
    }
    
    private suspend fun performTextEmbeddingHealthCheck(): Boolean {
        return try {
            val testEmbedding: List<Float> = embeddingService.generateQueryEmbedding("health check")
            if (testEmbedding.isEmpty()) {
                logger.warn { "Text embedding service returned empty result" }
                false
            } else {
                logger.debug { "Text embedding health check passed (${testEmbedding.size} dimensions)" }
                true
            }
        } catch (e: Exception) {
            logger.warn(e) { "Text embedding health check failed: ${e.message}" }
            false
        }
    }
    
    private suspend fun performCodeEmbeddingHealthCheck(): Boolean {
        return try {
            val testCode = "fun healthCheck() { return true }"
            val testEmbedding: List<Float> = embeddingService.generateEmbedding(testCode)
            if (testEmbedding.isEmpty()) {
                logger.warn { "Code embedding service returned empty result" }
                false
            } else {
                logger.debug { "Code embedding health check passed (${testEmbedding.size} dimensions)" }
                true
            }
        } catch (e: Exception) {
            logger.warn(e) { "Code embedding health check failed: ${e.message}" }
            false
        }
    }
    
    private fun handleHealthCheckFailure() {
        consecutiveFailures++
        logger.warn { "Embedding health check failed (consecutive failures: $consecutiveFailures)" }
        
        if (consecutiveFailures >= maxConsecutiveFailures) {
            if (isHealthy) {
                logger.error { "Marking embedding services as unhealthy after $consecutiveFailures consecutive failures" }
                isHealthy = false
            }
            
            // Attempt recovery
            attemptRecovery()
        }
    }
    
    private fun handleOutOfMemoryError() {
        logger.error { "Out of memory error detected in embedding service" }
        isHealthy = false
        consecutiveFailures = maxConsecutiveFailures
        
        // Force garbage collection
        System.gc()
        
        // Wait a bit for GC to complete
        try {
            Thread.sleep(5000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        // Attempt recovery
        attemptRecovery()
    }
    
    private fun attemptRecovery() {
        try {
            logger.info { "Attempting to recover embedding services..." }
            
            // Trigger service reinitialization
            embeddingService.handleSettingsChangeEvent()
            
            logger.info { "Embedding service recovery attempt completed" }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to recover embedding services: ${e.message}" }
        }
    }
    
    /**
     * Get current health status
     */
    fun isHealthy(): Boolean = isHealthy
    
    /**
     * Get consecutive failure count
     */
    fun getConsecutiveFailures(): Int = consecutiveFailures
    
    /**
     * Force a health check (for testing or manual triggers)
     */
    suspend fun performImmediateHealthCheck(): HealthCheckResult {
        return try {
            val textHealth = performTextEmbeddingHealthCheck()
            val codeHealth = performCodeEmbeddingHealthCheck()
            
            HealthCheckResult(
                isHealthy = textHealth && codeHealth,
                textEmbeddingHealthy = textHealth,
                codeEmbeddingHealthy = codeHealth,
                consecutiveFailures = consecutiveFailures,
                lastCheckTime = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            logger.error(e) { "Immediate health check failed: ${e.message}" }
            HealthCheckResult(
                isHealthy = false,
                textEmbeddingHealthy = false,
                codeEmbeddingHealthy = false,
                consecutiveFailures = consecutiveFailures,
                lastCheckTime = System.currentTimeMillis(),
                error = e.message
            )
        }
    }
    
    /**
     * Reset health status (for manual recovery)
     */
    fun resetHealthStatus() {
        logger.info { "Manually resetting health status" }
        isHealthy = true
        consecutiveFailures = 0
    }
    
    /**
     * Get health metrics for monitoring
     */
    fun getHealthMetrics(): HealthMetrics {
        return HealthMetrics(
            isHealthy = isHealthy,
            consecutiveFailures = consecutiveFailures,
            maxConsecutiveFailures = maxConsecutiveFailures,
            uptime = getUptimeMetric()
        )
    }
    
    private fun getUptimeMetric(): Double {
        // Simple uptime calculation based on health status
        return if (isHealthy) 1.0 else 0.0
    }
}

/**
 * Data class representing health check result
 */
data class HealthCheckResult(
    val isHealthy: Boolean,
    val textEmbeddingHealthy: Boolean,
    val codeEmbeddingHealthy: Boolean,
    val consecutiveFailures: Int,
    val lastCheckTime: Long,
    val error: String? = null
)

/**
 * Data class representing health metrics
 */
data class HealthMetrics(
    val isHealthy: Boolean,
    val consecutiveFailures: Int,
    val maxConsecutiveFailures: Int,
    val uptime: Double
)