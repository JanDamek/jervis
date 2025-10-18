package com.jervis.service

/**
 * Platform-agnostic interface for debug window service.
 * Implementations handle LLM call debugging for different platforms (Desktop, Web, Mobile).
 */
interface IDebugWindowService {
    /**
     * Starts a new debug session for an LLM call.
     *
     * @param sessionId Unique identifier for the debug session
     * @param promptType Type of prompt being used (e.g., "PLANNER", "EXECUTOR")
     * @param systemPrompt System prompt sent to the LLM
     * @param userPrompt User prompt sent to the LLM
     */
    suspend fun startDebugSession(
        sessionId: String,
        promptType: String,
        systemPrompt: String,
        userPrompt: String,
    )

    /**
     * Appends a chunk of response from the LLM to the debug session.
     *
     * @param sessionId Unique identifier for the debug session
     * @param chunk Response chunk to append
     */
    suspend fun appendResponse(
        sessionId: String,
        chunk: String,
    )

    /**
     * Marks a debug session as completed.
     *
     * @param sessionId Unique identifier for the debug session
     */
    suspend fun completeSession(sessionId: String)

    /**
     * Shows the debug window (if applicable for the platform).
     */
    fun showDebugWindow()

    /**
     * Hides the debug window (if applicable for the platform).
     */
    fun hideDebugWindow()

    /**
     * Checks if the debug window is currently visible.
     *
     * @return true if debug window is visible, false otherwise
     */
    fun isDebugWindowVisible(): Boolean

    /**
     * Gets list of active debug session IDs.
     *
     * @return List of active session IDs
     */
    fun getActiveSessionIds(): List<String>
}
