package com.jervis.service.resilience

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.atomic.AtomicInteger

class DatabaseConnectionManagerTest {

    @Test
    fun `test retry mechanism with eventual success`() = runBlocking {
        val connectionManager = DatabaseConnectionManager()
        val attemptCount = AtomicInteger(0)
        
        val result = connectionManager.executeWithRetry(
            operationName = "Test Operation",
            maxRetries = 3,
            initialDelayMs = 100
        ) {
            val attempt = attemptCount.incrementAndGet()
            if (attempt < 3) {
                throw RuntimeException("Simulated failure on attempt $attempt")
            }
            "Success on attempt $attempt"
        }
        
        assertEquals("Success on attempt 3", result)
        assertEquals(3, attemptCount.get())
    }
    
    @Test
    fun `test retry mechanism with all failures`() = runBlocking {
        val connectionManager = DatabaseConnectionManager()
        val attemptCount = AtomicInteger(0)
        
        val result = connectionManager.executeWithRetry(
            operationName = "Test Operation",
            maxRetries = 2,
            initialDelayMs = 50
        ) {
            attemptCount.incrementAndGet()
            throw RuntimeException("Always fails")
        }
        
        assertNull(result)
        assertEquals(3, attemptCount.get()) // maxRetries + 1
    }
    
    @Test
    fun `test circuit breaker opens after failures`() = runBlocking {
        val connectionManager = DatabaseConnectionManager()
        val config = DatabaseConnectionManager.ResilienceConfig(
            maxRetries = 1,
            circuitBreakerFailureThreshold = 2,
            initialDelayMs = 50
        )
        val circuitBreaker = DatabaseConnectionManager.CircuitBreaker(config)
        
        // First failure
        val result1 = connectionManager.executeWithResilience(
            operationName = "Test Operation 1",
            config = config,
            circuitBreaker = circuitBreaker
        ) {
            throw RuntimeException("First failure")
        }
        assertNull(result1)
        assertEquals(DatabaseConnectionManager.CircuitBreakerState.CLOSED, circuitBreaker.getState())
        
        // Second failure - should open circuit
        val result2 = connectionManager.executeWithResilience(
            operationName = "Test Operation 2",
            config = config,
            circuitBreaker = circuitBreaker
        ) {
            throw RuntimeException("Second failure")
        }
        assertNull(result2)
        assertEquals(DatabaseConnectionManager.CircuitBreakerState.OPEN, circuitBreaker.getState())
        
        // Third attempt should be rejected immediately
        val result3 = connectionManager.executeWithResilience(
            operationName = "Test Operation 3",
            config = config,
            circuitBreaker = circuitBreaker
        ) {
            "Should not be executed"
        }
        assertNull(result3)
        assertEquals(DatabaseConnectionManager.CircuitBreakerState.OPEN, circuitBreaker.getState())
    }
    
    @Test
    fun `test health check with timeout`() = runBlocking {
        val connectionManager = DatabaseConnectionManager()
        
        // Successful health check
        val healthyResult = connectionManager.healthCheck(
            serviceName = "Test Service",
            timeoutMs = 1000
        ) {
            true
        }
        assertTrue(healthyResult)
        
        // Failed health check
        val unhealthyResult = connectionManager.healthCheck(
            serviceName = "Test Service",
            timeoutMs = 1000
        ) {
            false
        }
        assertFalse(unhealthyResult)
        
        // Timeout health check
        val timeoutResult = connectionManager.healthCheck(
            serviceName = "Test Service",
            timeoutMs = 100
        ) {
            Thread.sleep(200)
            true
        }
        assertFalse(timeoutResult)
    }
}