package com.jervis.joern.service

import com.jervis.common.client.IJoernClient
import com.jervis.common.dto.JoernQueryDto
import com.jervis.common.dto.JoernResultDto
import com.jervis.joern.domain.JoernRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

private val logger = KotlinLogging.logger {}

class JoernServiceImpl(
    private val runner: JoernRunner
) : IJoernClient {

    override suspend fun run(request: JoernQueryDto): JoernResultDto =
        withContext(Dispatchers.IO) {
            logger.info {
                "Received Joern query request: queryLength=${request.query.length}, hasProject=${request.projectZipBase64 != null}"
            }
            var workDir: Path? = null
            try {
                val b64 = request.projectZipBase64
                if (b64 != null) {
                    workDir = Files.createTempDirectory("joern-proj-")
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

    private fun unzipBase64To(base64: String, targetDir: Path) {
        val bytes = Base64.getDecoder().decode(base64)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outPath = targetDir.resolve(entry.name).normalize()
                if (!outPath.startsWith(targetDir)) {
                    throw IllegalArgumentException("Zip entry escapes target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(outPath)
                } else {
                    Files.createDirectories(outPath.parent)
                    Files.newOutputStream(outPath).use { out ->
                        zis.copyTo(out)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
