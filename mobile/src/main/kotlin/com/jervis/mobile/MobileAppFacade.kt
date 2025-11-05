package com.jervis.mobile

import com.jervis.service.IUserTaskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory

private val logger = KotlinLogging.logger {}

/**
 * Entry facade for Mobile UI. This is intentionally minimal and mirrors Desktop wiring:
 * - creates HTTP service proxies against the Server using Spring's @HttpExchange
 * - exposes suspending functions for UI (coroutines-first)
 */
class MobileAppFacade(
    private val bootstrap: MobileBootstrap,
) {
    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(bootstrap.serverBaseUrl)
            .build()
    }

    private val httpFactory: HttpServiceProxyFactory by lazy {
        HttpServiceProxyFactory
            .builderFor(WebClientAdapter.create(webClient))
            .build()
    }

    // Example of service proxy usage (types come from :common)
    private val userTaskService: IUserTaskService by lazy {
        httpFactory.createClient(IUserTaskService::class.java)
    }

    suspend fun refreshUserTasks(context: MobileContext): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Skeleton only — call kept as a placeholder to demonstrate flow.
            // e.g., val tasks = userTaskService.list(context.clientId, context.projectId)
            logger.info { "refreshUserTasks() called for client=${context.clientId} project=${context.projectId}" }
            Unit
        }
    }
}
