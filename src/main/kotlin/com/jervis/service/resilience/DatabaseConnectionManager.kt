package com.jervis.service.resilience

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.pow

/**
 * Database connection manager with resilience patterns including:
 * - Retry logic with exponential backoff
 * - Circuit breaker pattern
 * - Connection health monitoring
 * - Graceful degradation
 */
@Component
class DatabaseConnectionManager {
    private val logger = KotlinLogging.logger {}
    
    /**
     * Circuit breaker states
     */
    enum class CircuitBreakerState {
        CLOSED,    // Normal operation
        OPEN,      // Failing, reject requests
        HALF_OPEN  // Testing if service recovered
    }
    
    /**
     * Configuration for resilience patterns
     */
    data class ResilienceConfig(
        val maxRetries: Int = 5,
        val initialDelayMs: Long = 1000,
        val maxDelayMs: Long = 30000,
        val backoffMultiplier: Double = 2.0,
        val circuitBreakerFailureThreshold: Int = 5,
        val circuitBreakerRecoveryTimeoutMs: Long = 60000,
        val healthCheckIntervalMs: Long = 30000,
        val connectionTimeoutMs: Long = 10000
    )
    
    /**
     * Circuit breaker implementation
     */
    class CircuitBreaker(private val config: ResilienceConfig) {
        private val state = AtomicReference(CircuitBreakerState.CLOSED)
        private val failureCount = AtomicInteger(0)
        private val lastFailureTime = AtomicReference<Instant?>(null)
        private val halfOpenTestInProgress = AtomicBoolean(false)
        
        fun canExecute(): Boolean {
            return when (state.get()) {
                CircuitBreakerState.CLOSED -> true
                CircuitBreakerState.OPEN -> {
                    val lastFailure = lastFailureTime.get()
                    if (lastFailure != null && 
                        Duration.between(lastFailure, Instant.now()).toMillis() > config.circuitBreakerRecoveryTimeoutMs) {
                        // Try to transition to half-open
                        if (halfOpenTestInProgress.compareAndSet(false, true)) {
                            state.set(CircuitBreakerState.HALF_OPEN)
                            return true
                        }
                    }
                    false
                }
                CircuitBreakerState.HALF_OPEN -> {
                    // Only allow one test request at a time
                    halfOpenTestInProgress.compareAndSet(false, true)
                }
            }
        }
        
        fun onSuccess() {
            failureCount.set(0)
            lastFailureTime.set(null)
            halfOpenTestInProgress.set(false)
            state.set(CircuitBreakerState.CLOSED)
        }
        
        fun onFailure() {
            val failures = failureCount.incrementAndGet()
            lastFailureTime.set(Instant.now())
            halfOpenTestInProgress.set(false)
            
            if (failures >= config.circuitBreakerFailureThreshold) {
                state.set(CircuitBreakerState.OPEN)
            }
        }
        
        fun getState(): CircuitBreakerState = state.get()
        fun getFailureCount(): Int = failureCount.get()
    }
    
    /**
     * Execute operation with retry logic and circuit breaker
     */
    suspend fun <T> executeWithResilience(
        operationName: String,
        config: ResilienceConfig = ResilienceConfig(),
        circuitBreaker: CircuitBreaker = CircuitBreaker(config),
        operation: suspend () -> T
    ): T? {
        if (!circuitBreaker.canExecute()) {
            logger.warn { "Circuit breaker is OPEN for operation: $operationName" }
            return null
        }
        
        var lastException: Exception? = null
        var delay = config.initialDelayMs
        
        repeat(config.maxRetries + 1) { attempt ->
            try {
                logger.debug { "Executing $operationName (attempt ${attempt + 1}/${config.maxRetries + 1})" }
                
                val result = withTimeout(config.connectionTimeoutMs) {
                    operation()
                }
                
                circuitBreaker.onSuccess()
                logger.debug { "Successfully executed $operationName on attempt ${attempt + 1}" }
                return result
                
            } catch (e: Exception) {
                lastException = e
                logger.warn { "Attempt ${attempt + 1} failed for $operationName: ${e.message}" }
                
                if (attempt < config.maxRetries) {
                    logger.info { "Retrying $operationName in ${delay}ms..." }
                    delay(delay)
                    delay = min((delay * config.backoffMultiplier).toLong(), config.maxDelayMs)
                }
            }
        }
        
        circuitBreaker.onFailure()
        logger.error(lastException) { "All retry attempts failed for $operationName" }
        return null
    }
    
    /**
     * Execute operation with simple retry (without circuit breaker)
     */
    suspend fun <T> executeWithRetry(
        operationName: String,
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        operation: suspend () -> T
    ): T? {
        var lastException: Exception? = null
        var delay = initialDelayMs
        
        repeat(maxRetries + 1) { attempt ->
            try {
                logger.debug { "Executing $operationName (attempt ${attempt + 1}/${maxRetries + 1})" }
                return operation()
            } catch (e: Exception) {
                lastException = e
                logger.warn { "Attempt ${attempt + 1} failed for $operationName: ${e.message}" }
                
                if (attempt < maxRetries) {
                    logger.info { "Retrying $operationName in ${delay}ms..." }
                    delay(delay)
                    delay = (delay * 1.5).toLong()
                }
            }
        }
        
        logger.error(lastException) { "All retry attempts failed for $operationName" }
        return null
    }
    
    /**
     * Health check with timeout
     */
    suspend fun healthCheck(
        serviceName: String,
        timeoutMs: Long = 5000,
        healthCheckOperation: suspend () -> Boolean
    ): Boolean {
        return try {
            withTimeout(timeoutMs) {
                val isHealthy = healthCheckOperation()
                logger.debug { "Health check for $serviceName: ${if (isHealthy) "HEALTHY" else "UNHEALTHY"}" }
                isHealthy
            }
        } catch (e: Exception) {
            logger.warn { "Health check failed for $serviceName: ${e.message}" }
            false
        }
    }
    
    /**
     * Start periodic health monitoring
     */
    fun startHealthMonitoring(
        serviceName: String,
        intervalMs: Long = 30000,
        healthCheckOperation: suspend () -> Boolean,
        onHealthChange: (Boolean) -> Unit = {}
    ): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            var lastHealthStatus: Boolean? = null
            
            while (isActive) {
                try {
                    val isHealthy = healthCheck(serviceName, 5000, healthCheckOperation)
                    
                    if (lastHealthStatus != isHealthy) {
                        logger.info { "Health status changed for $serviceName: ${if (isHealthy) "HEALTHY" else "UNHEALTHY"}" }
                        onHealthChange(isHealthy)
                        lastHealthStatus = isHealthy
                    }
                    
                    delay(intervalMs)
                } catch (e: Exception) {
                    logger.error(e) { "Error during health monitoring for $serviceName" }
                    delay(intervalMs)
                }
            }
        }
    }
}