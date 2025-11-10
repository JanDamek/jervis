package com.jervis.whisper.service

import com.jervis.whisper.domain.Segment
import com.jervis.whisper.domain.Transcript
import com.jervis.whisper.domain.WhisperJob
import com.jervis.whisper.domain.WhisperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

@Service
class SimpleWhisperService : WhisperService {
    private val logger = KotlinLogging.logger {}

    override suspend fun transcribe(job: WhisperJob): Transcript =
        withContext(Dispatchers.IO) {
            val work = Files.createTempDirectory("whisper-")
            try {
                val audioPath: Path =
                    when (job) {
                        is WhisperJob.FromUrl -> downloadTo(work, job.url)
                        is WhisperJob.FromBase64 -> saveBase64(work, job.mimeType, job.data)
                    }
                val script = writePythonScript(work)
                val taskArg = "transcribe"
                val cmd = listOf("python3", script.toString(), audioPath.toString(), taskArg)
                logger.info { "Running faster-whisper: ${cmd.joinToString(" ")}" }
                val pb = ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true)
                val p = pb.start()
                val timeoutMs = DEFAULT_TIMEOUT_MS
                val completed = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!completed) {
                    p.destroyForcibly()
                    throw WhisperTimeoutException("Whisper timed out after $timeoutMs ms")
                }
                val exit = p.exitValue()
                val stdout = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
                if (exit != 0) {
                    throw WhisperProcessException("Whisper failed with exit=$exit, output=${stdout.take(1000)}")
                }
                parseTranscript(stdout)
            } finally {
                runCatching { work.toFile().deleteRecursively() }
            }
        }

    private fun parseTranscript(jsonStr: String): Transcript =
        try {
            val root = Json.parseToJsonElement(jsonStr).jsonObject
            val text = root["text"]?.jsonPrimitive?.content ?: ""
            val segs =
                root["segments"]?.jsonArray?.map { e ->
                    val o = e.jsonObject
                    val start = o["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    val end = o["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    val t = o["text"]?.jsonPrimitive?.content ?: ""
                    Segment(start, end, t)
                } ?: emptyList()
            Transcript(text = text, segments = segs)
        } catch (e: Exception) {
            throw WhisperProcessException("Failed to parse whisper JSON: ${e.message}")
        }

    private fun downloadTo(
        dir: Path,
        url: String,
    ): Path {
        val target = dir.resolve("input.bin")
        URI(url).toURL().openStream().use { input -> Files.copy(input, target) }
        return target
    }

    private fun saveBase64(
        dir: Path,
        mimeType: String,
        data: String,
    ): Path {
        val ext =
            when (mimeType.lowercase()) {
                "audio/wav", "wav" -> ".wav"
                "audio/mpeg", "mp3" -> ".mp3"
                "audio/mp4", "m4a", "mp4" -> ".m4a"
                "audio/ogg", "ogg" -> ".ogg"
                "audio/flac", "flac" -> ".flac"
                "audio/webm", "webm" -> ".webm"
                else -> ".bin"
            }
        val target = dir.resolve("input$ext")
        val bytes = Base64.getDecoder().decode(data)
        Files.write(target, bytes)
        return target
    }

    private fun writePythonScript(dir: Path): Path {
        val scriptContent =
            this::class.java
                .getResourceAsStream("/scripts/whisper_runner.py")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: throw IllegalStateException("Whisper Python script not found in resources")

        val path = dir.resolve("whisper_runner.py")
        Files.writeString(path, scriptContent)
        return path
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS: Long = 300_000 // 5 minutes
    }
}
