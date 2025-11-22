package com.jervis.service.gateway.core

import com.jervis.service.config.ProviderCapabilitiesService
import com.jervis.domain.model.ExecutionMode
import com.jervis.domain.model.ModelProviderEnum
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Manages concurrency limits per provider using Semaphores.
 * Provider concurrency is defined in models-config.yaml.
 *
 * NONBLOCKING providers (CPU qualifiers): Always execute immediately, never wait.
 * INTERRUPTIBLE providers (GPU models): Respect concurrency limits with foreground priority.
 *
 * This ensures back-pressure: if a provider's concurrency limit is reached,
 * callers will suspend until a permit is available.
 */
@Component
class ProviderConcurrencyManager(
    private val providerCapabilitiesService: ProviderCapabilitiesService,
) {
    private val semaphores = ConcurrentHashMap<ModelProviderEnum, Semaphore>()

    /**
     * Executes the given block with concurrency control for the specified provider.
     * NONBLOCKING providers execute immediately.
     * INTERRUPTIBLE providers wait for permits based on concurrency limits.
     */
    suspend fun <T> withConcurrencyControl(
        provider: ModelProviderEnum,
        block: suspend () -> T,
    ): T {
        val capabilities = providerCapabilitiesService.getProviderCapabilities(provider)
            ?: return block() // Provider not configured, execute immediately

        // NONBLOCKING providers always run immediately
        if (capabilities.executionMode == ExecutionMode.NONBLOCKING) {
            return block()
        }

        // INTERRUPTIBLE providers respect concurrency limits
        val maxConcurrent = capabilities.maxConcurrentRequests
        val semaphore =
            semaphores.computeIfAbsent(provider) {
                logger.info { "Initializing semaphore for provider $provider with maxConcurrentRequests=$maxConcurrent" }
                Semaphore(maxConcurrent)
            }

        if (semaphore.availablePermits == 0) {
            logger.debug { "Provider at capacity for $provider ($maxConcurrent). Waiting for a free permit..." }
        }

        return semaphore.withPermit {
            logger.debug { "Provider permit acquired for $provider, available=${semaphore.availablePermits}/$maxConcurrent" }
            try {
                block()
            } finally {
                logger.debug { "Provider permit released for $provider, available=${semaphore.availablePermits}/$maxConcurrent" }
            }
        }
    }
}
