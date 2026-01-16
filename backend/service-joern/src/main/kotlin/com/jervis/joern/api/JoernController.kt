package com.jervis.joern.api

import com.jervis.common.client.IJoernClient
import com.jervis.common.dto.JoernQueryDto
import com.jervis.common.dto.JoernResultDto
import com.jervis.joern.domain.JoernRunner
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.rpc.krpc.ktor.server.*
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class JoernController(
    private val runner: JoernRunner,
    @Value("\${server.port:8080}") private val port: Int,
) : IJoernClient {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun startRpcServer() {
        Thread {
            embeddedServer(Netty, port = port, host = "0.0.0.0") {
                install(WebSockets)
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        explicitNulls = false
                    })
                }
                routing {
                    get("/health") {
                        call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                    }
                    get("/actuator/health") {
                        call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                    }

                    post("/api/joern/run") {
                        val req = call.receive<JoernQueryDto>()
                        call.respond(run(req))
                    }

                    get("/") {
                        call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                    }

                    rpc("/rpc") {
                        rpcConfig {
                            serialization {
                                cbor()
                            }
                        }
                        registerService<IJoernClient> { this@JoernController }
                    }
                }
            }.start(wait = true)
        }.start()
        logger.info { "Joern RPC server started on port $port at /rpc" }
    }

    override suspend fun run(
        @RequestBody request: JoernQueryDto,
    ): JoernResultDto =
        withContext(Dispatchers.IO) {
            logger.info {
                "Received Joern query request: queryLength=${request.query.length}, hasProject=${request.projectZipBase64 != null}"
            }
            var workDir: java.nio.file.Path? = null
            try {
                val b64 = request.projectZipBase64
                if (b64 != null) {
                    workDir =
                        java.nio.file.Files
                            .createTempDirectory("joern-proj-")
                    unzipBase64To(b64, workDir)
                    logger.debug { "Unzipped project to: $workDir" }
                }
                val r = runner.run(request.query, workDir)
                logger.info {
                    "Joern query completed: exitCode=${r.exitCode}, stdoutLength=${r.stdout.length}, stderrLength=${r.stderr?.length ?: 0}"
                }
                JoernResultDto(stdout = r.stdout, stderr = r.stderr, exitCode = r.exitCode)
            } catch (e: Exception) {
                logger.error(e) { "Joern query failed: ${e.message}" }
                throw e
            } finally {
                runCatching { workDir?.toFile()?.deleteRecursively() }
            }
        }

    private fun unzipBase64To(
        base64: String,
        targetDir: java.nio.file.Path,
    ) {
        val bytes =
            java.util.Base64
                .getDecoder()
                .decode(base64)
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outPath = targetDir.resolve(entry.name).normalize()
                if (!outPath.startsWith(targetDir)) {
                    throw IllegalArgumentException("Zip entry escapes target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    java.nio.file.Files
                        .createDirectories(outPath)
                } else {
                    java.nio.file.Files
                        .createDirectories(outPath.parent)
                    java.nio.file.Files.newOutputStream(outPath).use { out ->
                        zis.copyTo(out)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
