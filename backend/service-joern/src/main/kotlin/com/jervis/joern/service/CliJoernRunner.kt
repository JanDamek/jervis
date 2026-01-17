package com.jervis.joern.service

import com.jervis.joern.domain.JoernRunner
import com.jervis.joern.domain.RunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.nio.file.Files

class CliJoernRunner : JoernRunner {
    private val logger = KotlinLogging.logger {}

    override suspend fun run(
        query: String,
        workingDir: java.nio.file.Path?,
    ): RunResult =
        withContext(Dispatchers.IO) {
            val joern = System.getenv("JOERN_HOME")?.let { "$it/joern-cli/joern" } ?: "joern"
            val script = Files.createTempFile("joern-", ".sc")
            try {
                Files.writeString(script, query)
                val cmd = listOf(joern, "--script", script.toString())
                logger.info {
                    "Executing Joern: ${
                        cmd.joinToString(
                            " ",
                        )
                    } in dir=${workingDir?.toString() ?: "(default)"}"
                }
                val pb =
                    ProcessBuilder(cmd)
                        .directory(workingDir?.toFile())
                        .redirectErrorStream(true)
                val p = pb.start()
                p.waitFor()
                val exit = p.exitValue()
                val output = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
                if (exit != 0) {
                    throw JoernProcessException("Joern failed with exit=$exit. Output: ${output.take(4000)}")
                }
                RunResult(stdout = output, stderr = null, exitCode = exit)
            } finally {
                runCatching { Files.deleteIfExists(script) }
            }
        }
}
