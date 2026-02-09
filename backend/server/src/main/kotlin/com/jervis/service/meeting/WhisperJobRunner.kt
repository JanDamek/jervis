package com.jervis.service.meeting

import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

private val logger = KotlinLogging.logger {}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Runs Whisper transcription as a K8s Job (in-cluster) or subprocess (local dev).
 *
 * Same dual-mode pattern as JoernClient in the Python KB service.
 * Audio file must already exist on PVC at the given path.
 * Result is written to {workspace}/.jervis/whisper-result.json.
 */
@Service
class WhisperJobRunner {

    companion object {
        private val WHISPER_IMAGE =
            System.getenv("WHISPER_IMAGE") ?: "registry.damek-soft.eu/jandamek/jervis-whisper:latest"
        private val K8S_NAMESPACE = System.getenv("K8S_NAMESPACE") ?: "jervis"
        private val PVC_NAME = System.getenv("DATA_PVC_NAME") ?: "jervis-data-pvc"
        private val PVC_MOUNT = System.getenv("DATA_PVC_MOUNT") ?: "/opt/jervis/data"
        private val IN_CLUSTER = Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/token").toFile().exists()
        private const val JOB_TIMEOUT_SECONDS = 600L
        private const val POLL_INTERVAL_SECONDS = 5L
    }

    /**
     * Transcribe an audio file using Whisper.
     *
     * @param audioFilePath Absolute path to the audio file on PVC
     * @param workspacePath Absolute path to the workspace directory on PVC
     * @return WhisperResult with transcription text and segments
     */
    suspend fun transcribe(audioFilePath: String, workspacePath: String): WhisperResult {
        val jervisDir = Paths.get(workspacePath).resolve(".jervis")
        val resultFile = jervisDir.resolve("whisper-result.json")

        withContext(Dispatchers.IO) {
            Files.createDirectories(jervisDir)
            Files.deleteIfExists(resultFile)
        }

        try {
            if (IN_CLUSTER) {
                runK8sJob(audioFilePath, workspacePath)
            } else {
                runLocal(audioFilePath, resultFile)
            }

            // Read result
            return withContext(Dispatchers.IO) {
                if (Files.exists(resultFile)) {
                    val content = Files.readString(resultFile)
                    json.decodeFromString<WhisperResult>(content)
                } else {
                    WhisperResult(text = "", segments = emptyList(), error = "Whisper completed but no result file found")
                }
            }
        } finally {
            // Clean up result file
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(resultFile)
            }
        }
    }

    private suspend fun runK8sJob(audioFilePath: String, workspacePath: String) {
        val jobName = "whisper-${UUID.randomUUID().toString().substring(0, 8)}"

        logger.info { "Creating Whisper K8s Job: $jobName (audio=$audioFilePath, workspace=$workspacePath)" }

        val job = JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(K8S_NAMESPACE)
                .addToLabels("app", "jervis-whisper")
                .addToLabels("type", "job")
            .endMetadata()
            .withNewSpec()
                .withTtlSecondsAfterFinished(300)
                .withBackoffLimit(0)
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels("app", "jervis-whisper")
                        .addToLabels("type", "job")
                    .endMetadata()
                    .withNewSpec()
                        .withRestartPolicy("Never")
                        .addNewContainer()
                            .withName("whisper")
                            .withImage(WHISPER_IMAGE)
                            .withImagePullPolicy("Always")
                            .withEnv(
                                EnvVarBuilder().withName("WORKSPACE").withValue(workspacePath).build(),
                                EnvVarBuilder().withName("AUDIO_FILE").withValue(audioFilePath).build(),
                            )
                            .withVolumeMounts(
                                VolumeMountBuilder()
                                    .withName("data")
                                    .withMountPath(PVC_MOUNT)
                                    .build(),
                            )
                            .withNewResources()
                                .addToRequests("memory", io.fabric8.kubernetes.api.model.Quantity("256Mi"))
                                .addToRequests("cpu", io.fabric8.kubernetes.api.model.Quantity("500m"))
                                .addToLimits("memory", io.fabric8.kubernetes.api.model.Quantity("2Gi"))
                                .addToLimits("cpu", io.fabric8.kubernetes.api.model.Quantity("2"))
                            .endResources()
                        .endContainer()
                        .withVolumes(
                            VolumeBuilder()
                                .withName("data")
                                .withPersistentVolumeClaim(
                                    PersistentVolumeClaimVolumeSourceBuilder()
                                        .withClaimName(PVC_NAME)
                                        .build(),
                                )
                                .build(),
                        )
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build()

        withContext(Dispatchers.IO) {
            KubernetesClientBuilder().build().use { client ->
                client.batch().v1().jobs()
                    .inNamespace(K8S_NAMESPACE)
                    .resource(job)
                    .create()

                // Poll for completion
                var elapsed = 0L
                while (elapsed < JOB_TIMEOUT_SECONDS) {
                    delay(POLL_INTERVAL_SECONDS * 1000)
                    elapsed += POLL_INTERVAL_SECONDS

                    val status = client.batch().v1().jobs()
                        .inNamespace(K8S_NAMESPACE)
                        .withName(jobName)
                        .get()
                        ?.status

                    if (status == null) {
                        logger.warn { "Whisper Job $jobName status is null, retrying..." }
                        continue
                    }

                    if ((status.succeeded ?: 0) > 0) {
                        logger.info { "Whisper Job $jobName completed successfully" }
                        return@withContext
                    }

                    if ((status.failed ?: 0) > 0) {
                        throw RuntimeException("Whisper Job $jobName failed")
                    }
                }

                throw RuntimeException("Whisper Job $jobName timed out after ${JOB_TIMEOUT_SECONDS}s")
            }
        }
    }

    private suspend fun runLocal(audioFilePath: String, resultFile: Path) {
        withContext(Dispatchers.IO) {
            val whisperScript = findWhisperRunner()
            val cmd = listOf("python3", whisperScript, audioFilePath, "transcribe", "base")

            logger.info { "Running Whisper locally: ${cmd.joinToString(" ")}" }

            val process = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                logger.error { "Whisper process failed (exit=$exitCode): $stderr" }
                // Write error result
                Files.writeString(
                    resultFile,
                    """{"text": "", "segments": [], "error": "Whisper failed (exit=$exitCode): $stderr"}""",
                )
            } else {
                Files.writeString(resultFile, stdout)
            }
        }
    }

    private fun findWhisperRunner(): String {
        // Check common locations
        val candidates = listOf(
            "backend/service-whisper/whisper_runner.py",
            "../service-whisper/whisper_runner.py",
            "/opt/jervis/whisper/whisper_runner.py",
        )
        for (candidate in candidates) {
            if (Paths.get(candidate).toFile().exists()) return candidate
        }
        // Fall back to expecting it on PATH or in current dir
        return "whisper_runner.py"
    }
}

@Serializable
data class WhisperResult(
    val text: String,
    val segments: List<WhisperSegment> = emptyList(),
    val error: String? = null,
)

@Serializable
data class WhisperSegment(
    val start: Double,
    val end: Double,
    val text: String,
)
