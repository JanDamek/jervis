package com.jervis.service.gateway.core

import com.jervis.configuration.prompts.ProviderCapabilitiesService
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
 * NONBLOCKING providers (CPU qualifiers): Respect concurrency limits, cannot be interrupted.
 * INTERRUPTIBLE providers (GPU models): Foreground tasks can interrupt respect concurrency limits.
 *
 * ALL providers respect maxConcurrentRequests to prevent overwhelming the backend server.
 * This ensures back-pressure: if a provider's concurrency limit is reached,
 * callers will suspend indefinitely until a permit is available.
 * HTTP timeout will handle stuck requests.
 */
@Component
class ProviderConcurrencyManager(
    private val providerCapabilitiesService: ProviderCapabilitiesService,
) {
    private val semaphores = ConcurrentHashMap<ModelProviderEnum, Semaphore>()

    /**
     * Executes the given block with concurrency control for the specified provider.
     * Both NONBLOCKING and INTERRUPTIBLE providers respect concurrency limits.
     * The difference is in interruption behavior (handled elsewhere), not in concurrency control.
     */
    suspend fun <T> withConcurrencyControl(
        provider: ModelProviderEnum,
        block: suspend () -> T,
    ): T {
        val capabilities = providerCapabilitiesService.getProviderCapabilities(provider)

        val maxConcurrent = capabilities.maxConcurrentRequests
        val semaphore =
            semaphores.computeIfAbsent(provider) {
                logger.info {
                    "Initializing semaphore for provider $provider with maxConcurrentRequests=$maxConcurrent"
                }
                Semaphore(maxConcurrent)
            }

        if (semaphore.availablePermits == 0) {
            logger.warn { "Provider at capacity for $provider ($maxConcurrent). Waiting for a free permit..." }
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
