package com.jervis.router

import com.jervis.router.config.ModelCatalog
import com.jervis.router.config.RouterConfig
import com.jervis.router.coord.ClientTierCache
import com.jervis.router.coord.ServerCallbackClient
import com.jervis.router.coord.WhisperCoordinator
import com.jervis.router.core.ActiveRequestsLogger
import com.jervis.router.core.GpuIdleNotifier
import com.jervis.router.core.RequestQueue
import com.jervis.router.core.RequestRouter
import com.jervis.router.core.ReservationGuard
import com.jervis.router.core.ReservationManager
import com.jervis.router.core.WarmupLoop
import com.jervis.router.gpu.GpuPool
import com.jervis.router.gpu.ModelLoader
import com.jervis.router.grpc.OllamaRouterGrpcServer
import com.jervis.router.grpc.RouterAdminGrpcImpl
import com.jervis.router.grpc.RouterInferenceGrpcImpl
import com.jervis.router.proxy.OllamaProxy
import com.jervis.router.proxy.OpenRouterCatalog
import com.jervis.router.proxy.OpenRouterProxy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    val config = RouterConfig.load()
    logger.info {
        "Starting Ollama Router on grpc=:${config.grpcPort} health=${config.healthHost}:${config.healthPort}, " +
            "gpu_backends=${config.gpuBackends}, kotlin_server=${config.kotlinServerHost}:${config.kotlinServerGrpcPort}"
    }

    if (config.gpuBackends.isEmpty()) {
        logger.warn { "GPU_BACKENDS env var is empty — router has no upstream targets" }
    }

    val applicationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val ollamaHttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }
    val mgmtHttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 30_000L
        }
    }

    val gpuPool = GpuPool(config.gpuBackends, config, mgmtHttpClient)
    val modelLoader = ModelLoader(config, mgmtHttpClient)
    val whisperCoordinator = WhisperCoordinator(gpuPool, modelLoader, config.whisperGpuMaxHoldS)

    val ollamaProxy = OllamaProxy(ollamaHttpClient) { backend, error ->
        if (backend.healthy) {
            logger.warn { "GPU ${backend.name} unreachable: $error — marking unhealthy" }
            backend.healthy = false
        }
    }
    val openRouterHttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }
    val openRouterProxy = OpenRouterProxy(openRouterHttpClient)
    val serverCallback = ServerCallbackClient(config.kotlinServerHost, config.kotlinServerGrpcPort)
    val openRouterCatalog = OpenRouterCatalog(serverCallback)
    val clientTierCache = ClientTierCache(config.mongodbUri, config.clientTierCacheTtlS)

    val reservationManager = ReservationManager(gpuPool, config) { gpuName ->
        // Slot freed → wake all dispatchers (we don't know which group benefits).
        // RequestQueue#notifySlotFreed wired below after construction.
    }

    val requestQueue = RequestQueue(
        gpuPool = gpuPool,
        ollamaProxy = ollamaProxy,
        modelLoader = modelLoader,
        whisperCoordinator = whisperCoordinator,
        routerConfig = config,
        httpClient = mgmtHttpClient,
        onCriticalActivity = { gpuName ->
            applicationScope.launch { reservationManager.notifyCriticalActivity(gpuName) }
        },
        onGpuRecovery = {
            applicationScope.launch { gpuPool.checkHealth() }
        },
    )

    val requestRouter = RequestRouter(
        config = config,
        gpuPool = gpuPool,
        requestQueue = requestQueue,
        openRouterCatalog = openRouterCatalog,
        openRouterProxy = openRouterProxy,
        whisperCoordinator = whisperCoordinator,
        clientTierCache = clientTierCache,
    )

    val inferenceImpl = RouterInferenceGrpcImpl(requestRouter)
    val adminImpl = RouterAdminGrpcImpl(openRouterCatalog, whisperCoordinator, clientTierCache, ollamaHttpClient)
    val grpcServer = OllamaRouterGrpcServer(inferenceImpl, adminImpl, config.grpcPort)

    val reservationGuard = ReservationGuard(reservationManager, config)
    val activeRequestsLogger = ActiveRequestsLogger(gpuPool)
    val gpuIdleNotifier = GpuIdleNotifier(gpuPool, config, serverCallback)
    val warmupLoop = WarmupLoop(gpuPool, config, mgmtHttpClient)

    runBlocking {
        gpuPool.syncState()
        preloadModelSets(gpuPool, modelLoader, config)
        runCatching { openRouterCatalog.loadPersistedStats() }
            .onFailure { logger.warn { "loadPersistedStats failed: ${it.message}" } }
    }
    requestQueue.start()
    reservationGuard.start(applicationScope)
    activeRequestsLogger.start(applicationScope)
    gpuIdleNotifier.start(applicationScope)
    warmupLoop.start(applicationScope)
    grpcServer.start()

    val healthJson = Json { ignoreUnknownKeys = true; prettyPrint = true; explicitNulls = false }
    embeddedServer(Netty, port = config.healthPort, host = config.healthHost) {
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(healthJson) }
        routing {
            get("/") {
                call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
            }
            get("/health") {
                call.respondText(
                    """{"status":"UP","service":"ollama-router","grpcPort":${config.grpcPort}}""",
                    ContentType.Application.Json,
                )
            }
        }
    }.start(wait = false)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info { "Shutting down Ollama Router" }
            grpcServer.stop()
            warmupLoop.stop()
            gpuIdleNotifier.stop()
            activeRequestsLogger.stop()
            reservationGuard.stop()
            requestQueue.stop()
            serverCallback.close()
            clientTierCache.close()
            ollamaHttpClient.close()
            openRouterHttpClient.close()
            mgmtHttpClient.close()
        },
    )

    logger.info { "Ollama Router started" }
    Thread.currentThread().join()
}

private suspend fun preloadModelSets(gpuPool: GpuPool, modelLoader: ModelLoader, config: RouterConfig) {
    val onDemand = ModelCatalog.modelSets.values.filter { it.keepAlive != "-1" }.flatMap { it.models }.toSet()
    for (backend in gpuPool.healthy) {
        val gpuModels = ModelCatalog.gpuModelSets[backend.name.value]
            ?: listOf(config.orchestratorModel)
        for (model in gpuModels) {
            if (model in onDemand) {
                logger.info { "Skipping on-demand model $model on GPU ${backend.name} (loaded when requested)" }
                continue
            }
            if (backend.hasModel(model)) {
                logger.info { "GPU ${backend.name} already has $model loaded" }
                continue
            }
            logger.info { "Preloading $model on GPU ${backend.name}" }
            val ok = modelLoader.loadModel(backend, model)
            if (!ok) logger.warn { "Failed to preload $model on GPU ${backend.name}" }
        }
    }
}

