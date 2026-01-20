package com.jervis.common.client

import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable

/**
 * RPC interface for coding services (Aider, OpenHands, Junie).
 * Each service implements this interface and provides code generation/modification capabilities.
 */
@Rpc
interface ICodingClient {
    /**
     * Execute a coding task.
     *
     * @param request The coding task request with instructions and context
     * @return The result including generated/modified code and execution status
     */
    suspend fun execute(request: CodingRequest): CodingResult
}

@Serializable
data class CodingRequest(
    /**
     * The coding instructions/task description.
     * Should be clear and specific about what needs to be done.
     */
    val instructions: String,

    /**
     * Optional list of file paths to focus on.
     * For Aider: files to modify
     * For OpenHands/Junie: context files (they can access whole project)
     */
    val files: List<String> = emptyList(),

    /**
     * Optional project context as base64-encoded ZIP archive.
     * If not provided, service will work with files from its local workspace.
     */
    val projectZipBase64: String? = null,

    /**
     * Optional verification command to run after code generation.
     * Example: "./gradlew test", "npm run test", "pytest"
     */
    val verifyCommand: String? = null,

    /**
     * Maximum number of iterations for self-correction.
     * Default: 3
     */
    val maxIterations: Int = 3,
)

@Serializable
data class CodingResult(
    /**
     * Whether the coding task was successful.
     */
    val success: Boolean,

    /**
     * Generated or modified code/files as base64-encoded ZIP archive.
     * Contains only changed files.
     */
    val resultZipBase64: String? = null,

    /**
     * Textual summary of what was done.
     */
    val summary: String,

    /**
     * Detailed log/output from the coding process.
     */
    val log: String = "",

    /**
     * Error message if success = false.
     */
    val errorMessage: String? = null,

    /**
     * Verification result if verifyCommand was provided.
     */
    val verificationResult: VerificationResult? = null,
)

@Serializable
data class VerificationResult(
    /**
     * Whether verification passed.
     */
    val passed: Boolean,

    /**
     * Output from the verification command.
     */
    val output: String,

    /**
     * Exit code from the verification command.
     */
    val exitCode: Int,
)
