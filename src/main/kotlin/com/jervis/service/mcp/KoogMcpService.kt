package com.jervis.service.mcp

import com.jervis.service.vectordb.VectorStorageService
import com.jervis.service.llm.LlmCoordinator
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * Implementation of the Model Context Protocol (MCP) using the Koog library.
 * This service enables models to interact with external systems like terminals, email, and other applications.
 */
@Service
class KoogMcpService(
    private val llmCoordinator: LlmCoordinator,
    private val vectorDbService: VectorStorageService,
) {
    private val logger = KotlinLogging.logger {}
    private val executor = Executors.newCachedThreadPool()

    /**
     * Process a query with MCP capabilities
     *
     * @param query The user query
     * @param context The context for the query
     * @param options Additional options for processing
     * @return The response with MCP capabilities
     */
    suspend fun processQuery(
        query: String,
        context: String,
        options: Map<String, Any> = emptyMap(),
    ): McpResponse {
        logger.info { "Processing query with MCP: ${query.take(50)}..." }

        // Extract messages from options if available
        @Suppress("UNCHECKED_CAST")
        val messages = options["messages"] as? List<Any> ?: emptyList()

        // If we have messages, process them as a list
        if (messages.isNotEmpty()) {
            return processMessagesList(messages, context, options)
        }

        // If no messages, process as a single query
        return processSingleQuery(query, context, options)
    }

    /**
     * Process a list of messages sequentially
     *
     * @param messages The list of messages to process
     * @param context The context for the query
     * @param options Additional options for processing
     * @return The combined response with MCP capabilities
     */
    private suspend fun processMessagesList(
        messages: List<Any>,
        context: String,
        options: Map<String, Any> = emptyMap(),
    ): McpResponse {
        logger.info { "Processing a list of ${messages.size} messages with MCP" }

        // Accumulate responses for each message
        val responsesList = mutableListOf<McpResponse>()
        var accumulatedContext = context

        // Process each message sequentially
        for (message in messages) {
            val role = (message as? Map<*, *>)?.get("role") as? String ?: "unknown"
            val content = (message as? Map<*, *>)?.get("content") as? String ?: ""

            // Only process user messages
            if (role == "user" && content.isNotBlank()) {
                // Process the message with the accumulated context
                val messageResponse = processSingleQuery(content, accumulatedContext, options)
                responsesList.add(messageResponse)

                // Update the accumulated context with this message and its response
                accumulatedContext += "\n[$role]: $content\n[assistant]: ${messageResponse.answer}"
            }
        }

        // If no responses were generated, return an empty response
        if (responsesList.isEmpty()) {
            return McpResponse(
                answer = "No valid messages to process",
                model = "none",
                promptTokens = 0,
                completionTokens = 0,
                totalTokens = 0,
                actions = emptyList(),
                actionResults = emptyMap(),
            )
        }

        // Combine all responses
        val combinedActions = responsesList.flatMap { it.actions }.distinctBy { "${it.type}:${it.content}" }
        val combinedActionResults =
            responsesList
                .flatMap { it.actionResults.entries }
                .associate { it.key to it.value }

        // Calculate total tokens
        val totalPromptTokens = responsesList.sumOf { it.promptTokens }
        val totalCompletionTokens = responsesList.sumOf { it.completionTokens }
        val totalTokens = responsesList.sumOf { it.totalTokens }

        // Get the model from the last response
        val model = responsesList.last().model

        // Combine all answers
        val combinedAnswer =
            responsesList
                .mapIndexed { index, response ->
                    "Response ${index + 1}: ${response.answer}"
                }.joinToString("\n\n")

        return McpResponse(
            answer = combinedAnswer,
            model = model,
            promptTokens = totalPromptTokens,
            completionTokens = totalCompletionTokens,
            totalTokens = totalTokens,
            actions = combinedActions,
            actionResults = combinedActionResults,
        )
    }

    /**
     * Process a single query with MCP capabilities
     *
     * @param query The user query
     * @param context The context for the query
     * @param options Additional options for processing
     * @return The response with MCP capabilities
     */
    private suspend fun processSingleQuery(
        query: String,
        context: String,
        options: Map<String, Any> = emptyMap(),
    ): McpResponse {
        // 1. Analyze the query to determine if it requires external system interaction
        val mcpActions = analyzeQueryForMcpActions(query)

        // 2. Process the query with the LLM coordinator
        val llmResponse = llmCoordinator.processQuery(query, context, options)

        // 3. Execute any MCP actions
        val actionResults = mutableMapOf<String, String>()
        mcpActions.forEach { action ->
            try {
                val result = executeAction(action, llmResponse.answer)
                actionResults[action.type] = result

                // Store the action and result in the vector database for future reference
                storeActionInVectorDb(action, result, query, options)
            } catch (e: Exception) {
                logger.error(e) { "Error executing MCP action ${action.type}: ${e.message}" }
                actionResults[action.type] = "Error: ${e.message}"
            }
        }

        // 4. If there were actions, generate a final response that incorporates the action results
        val finalResponse =
            if (actionResults.isNotEmpty()) {
                val actionContext = buildActionContext(actionResults)
                llmCoordinator.processQuery(
                    "Based on the original query: '$query' and the results of the actions: $actionContext, provide a comprehensive response.",
                    context + "\n" + actionContext,
                    options,
                )
            } else {
                llmResponse
            }

        return McpResponse(
            answer = finalResponse.answer,
            model = finalResponse.model,
            promptTokens = finalResponse.promptTokens,
            completionTokens = finalResponse.completionTokens,
            totalTokens = finalResponse.totalTokens,
            actions = mcpActions,
            actionResults = actionResults,
        )
    }

    /**
     * Analyze a query to determine if it requires MCP actions
     *
     * @param query The query to analyze
     * @return A list of MCP actions to execute
     */
    private suspend fun analyzeQueryForMcpActions(query: String): List<McpAction> {
        // Use the LLM to determine if the query requires MCP actions
        val analysisPrompt =
            """
            Analyze the following query and determine if it requires external system interactions.
            If it does, list the specific actions needed in a structured format.

            Query: $query

            For each action, specify:
            1. Type (terminal, email, application)
            2. Command or content
            3. Parameters

            Format your response as a JSON array of actions.
            """.trimIndent()

        val analysisResponse = llmCoordinator.processQuery(analysisPrompt, "")

        // Parse the response to extract actions
        // This is a simplified implementation - in a real system, you would use proper JSON parsing
        val actions = mutableListOf<McpAction>()

        // Simple parsing logic for demonstration
        if (analysisResponse.answer.contains("terminal")) {
            val command = extractCommand(analysisResponse.answer)
            actions.add(McpAction("terminal", command, emptyMap()))
        }

        if (analysisResponse.answer.contains("email")) {
            val recipient = extractEmailRecipient(analysisResponse.answer)
            val subject = extractEmailSubject(analysisResponse.answer)
            val content = extractEmailContent(analysisResponse.answer)

            actions.add(
                McpAction(
                    "email",
                    content,
                    mapOf("recipient" to recipient, "subject" to subject),
                ),
            )
        }

        if (analysisResponse.answer.contains("application")) {
            val appName = extractApplicationName(analysisResponse.answer)
            val appAction = extractApplicationAction(analysisResponse.answer)

            actions.add(
                McpAction(
                    "application",
                    appAction,
                    mapOf("name" to appName),
                ),
            )
        }

        return actions
    }

    /**
     * Execute an MCP action
     *
     * @param action The action to execute
     * @param context Additional context for the action
     * @return The result of the action
     */
    private fun executeAction(
        action: McpAction,
        context: String,
    ): String =
        when (action.type) {
            "terminal" -> executeTerminalCommand(action.content)
            "email" ->
                sendEmail(
                    action.parameters["recipient"] as String,
                    action.parameters["subject"] as String,
                    action.content,
                )

            "application" ->
                interactWithApplication(
                    action.parameters["name"] as String,
                    action.content,
                )

            else -> "Unsupported action type: ${action.type}"
        }

    /**
     * Execute a terminal command
     *
     * @param command The command to execute
     * @return The output of the command
     */
    private fun executeTerminalCommand(command: String): String {
        logger.info { "Executing terminal command: $command" }

        try {
            // Security check - in a real implementation, you would have more robust security measures
            if (command.contains("rm") || command.contains("sudo") || command.contains(";")) {
                return "Command rejected for security reasons"
            }

            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            while (errorReader.readLine().also { line = it } != null) {
                output.append("ERROR: ").append(line).append("\n")
            }

            val exitCode = process.waitFor()
            output.append("Exit code: $exitCode")

            return output.toString()
        } catch (e: Exception) {
            logger.error(e) { "Error executing terminal command: ${e.message}" }
            return "Error executing command: ${e.message}"
        }
    }

    /**
     * Send an email (simulated)
     *
     * @param recipient The email recipient
     * @param subject The email subject
     * @param content The email content
     * @return The result of the email sending operation
     */
    private fun sendEmail(
        recipient: String,
        subject: String,
        content: String,
    ): String {
        logger.info { "Simulating email to: $recipient, subject: $subject" }

        // In a real implementation, this would use JavaMail API to send actual emails
        // For now, we'll just log the email details and return a success message

        logger.info { "Email content: $content" }

        // Store the email in the vector database for future reference
        val emailDetails =
            """
            To: $recipient
            Subject: $subject

            $content
            """.trimIndent()

        // In a real implementation, you might want to store this in the vector database

        return "Email sent successfully to $recipient (simulated)"
    }

    /**
     * Interact with an external application
     *
     * @param appName The name of the application
     * @param action The action to perform
     * @return The result of the interaction
     */
    private fun interactWithApplication(
        appName: String,
        action: String,
    ): String {
        logger.info { "Interacting with application: $appName, action: $action" }

        // This is a placeholder implementation - in a real system, you would implement specific application integrations
        return "Interaction with $appName: $action (simulated)"
    }

    /**
     * Store an MCP action and its result in the vector database
     *
     * @param action The MCP action
     * @param result The result of the action
     * @param query The original query
     * @param options Additional options for processing
     */
    private fun storeActionInVectorDb(
        action: McpAction,
        result: String,
        query: String,
        options: Map<String, Any> = emptyMap(),
    ) {
        try {
            logger.info { "Storing MCP action in vector DB: ${action.type}" }

            // Generate an embedding for the action and result
            val content =
                """
                Query: $query

                Action Type: ${action.type}
                Action Content: ${action.content}
                Action Parameters: ${action.parameters}

                Result: $result
                """.trimIndent()

            // In a real implementation, you would use the embedding service to generate an embedding
            // For now, we'll use a simple placeholder embedding
            val embedding = List(1024) { 0.0f }

            // Extract projectId from options if available
            val projectIdLong = options["projectId"] as? Long
            val projectId = projectIdLong?.let { 
                try {
                    org.bson.types.ObjectId(it.toString())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }

            // Store the action in the vector database
            if (projectId != null) {
                vectorDbService.storeMcpAction(action, result, query, embedding, projectId)
            } else {
                logger.warn { "No valid projectId provided, skipping vector database storage" }
            }

            logger.info { "MCP action stored successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error storing MCP action in vector DB: ${e.message}" }
        }
    }

    /**
     * Build a context string from action results
     *
     * @param actionResults The results of MCP actions
     * @return A context string
     */
    private fun buildActionContext(actionResults: Map<String, String>): String {
        val context = StringBuilder()

        actionResults.forEach { (type, result) ->
            context.append("Action type: $type\n")
            context.append("Result: $result\n\n")
        }

        return context.toString()
    }

    /**
     * Extract a command from an analysis response
     *
     * @param response The analysis response
     * @return The extracted command
     */
    private fun extractCommand(response: String): String {
        // This is a simplified implementation - in a real system, you would use proper parsing
        val commandPattern = "command[\"':](.*?)[\"',}]".toRegex(RegexOption.IGNORE_CASE)
        val match = commandPattern.find(response)
        return match?.groupValues?.get(1)?.trim() ?: "ls -la"
    }

    /**
     * Extract an email recipient from an analysis response
     *
     * @param response The analysis response
     * @return The extracted email recipient
     */
    private fun extractEmailRecipient(response: String): String {
        // This is a simplified implementation - in a real system, you would use proper parsing
        val recipientPattern = "recipient[\"':](.*?)[\"',}]".toRegex(RegexOption.IGNORE_CASE)
        val match = recipientPattern.find(response)
        return match?.groupValues?.get(1)?.trim() ?: "user@example.com"
    }

    /**
     * Extract an email subject from an analysis response
     *
     * @param response The analysis response
     * @return The extracted email subject
     */
    private fun extractEmailSubject(response: String): String {
        // This is a simplified implementation - in a real system, you would use proper parsing
        val subjectPattern = "subject[\"':](.*?)[\"',}]".toRegex(RegexOption.IGNORE_CASE)
        val match = subjectPattern.find(response)
        return match?.groupValues?.get(1)?.trim() ?: "Message from JERVIS"
    }

    /**
     * Extract email content from an analysis response
     *
     * @param response The analysis response
     * @return The extracted email content
     */
    private fun extractEmailContent(response: String): String {
        // This is a simplified implementation - in a real system, you would use proper parsing
        val contentPattern = "content[\"':](.*?)[\"',}]".toRegex(RegexOption.IGNORE_CASE)
        val match = contentPattern.find(response)
        return match?.groupValues?.get(1)?.trim() ?: "This is an automated message from JERVIS."
    }

    /**
     * Extract an application name from an analysis response
     *
     * @param response The analysis response
     * @return The extracted application name
     */
    private fun extractApplicationName(response: String): String {
        // This is a simplified implementation - in a real system, you would use proper parsing
        val namePattern = "name[\"':](.*?)[\"',}]".toRegex(RegexOption.IGNORE_CASE)
        val match = namePattern.find(response)
        return match?.groupValues?.get(1)?.trim() ?: "default-app"
    }

    /**
     * Extract an application action from an analysis response
     *
     * @param response The analysis response
     * @return The extracted application action
     */
    private fun extractApplicationAction(response: String): String {
        // This is a simplified implementation - in a real system, you would use proper parsing
        val actionPattern = "action[\"':](.*?)[\"',}]".toRegex(RegexOption.IGNORE_CASE)
        val match = actionPattern.find(response)
        return match?.groupValues?.get(1)?.trim() ?: "default-action"
    }

    /**
     * Execute an MCP action asynchronously
     *
     * @param action The action to execute
     * @param context Additional context for the action
     * @return The result of the action
     */
    suspend fun executeActionAsync(
        action: McpAction,
        context: String,
    ): String = executeAction(action, context)
}

/**
 * Represents an MCP action to be executed
 */
data class McpAction(
    val type: String,
    val content: String,
    val parameters: Map<String, Any>,
)

/**
 * Response from the MCP service
 */
data class McpResponse(
    val answer: String,
    val model: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val actions: List<McpAction> = emptyList(),
    val actionResults: Map<String, String> = emptyMap(),
)
