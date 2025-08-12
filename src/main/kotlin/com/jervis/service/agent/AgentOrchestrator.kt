package com.jervis.service.agent

import com.jervis.domain.rag.RagDocumentFilter
import com.jervis.domain.rag.RagDocumentType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.indexer.EmbeddingService
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.project.ProjectService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Agent Orchestrator (Central Brain).
 *
 * This service implements the continuous cycle of analyzing tasks, retrieving information
 * from memory, planning, and executing actions. It is the central component of the agent
 * architecture, coordinating all other components.
 *
 * The orchestrator follows a four-step cycle:
 * 1. Analyze Task: Understand what is being asked
 * 2. Remember: Retrieve relevant information from memory
 * 3. Think and Plan: Decide on the best course of action
 * 4. Act: Execute the plan using available tools
 *
 * This cycle repeats until the task is completed.
 */
@Service
class AgentOrchestrator(
    private val llmCoordinator: LlmCoordinator,
    private val projectService: ProjectService,
    private val workingMemory: WorkingMemory,
    private val agentToolbox: AgentToolbox,
    private val filterGeneratorService: FilterGeneratorService,
    private val vectorStorageRepository: VectorStorageRepository,
    private val embeddingService: EmbeddingService,
) {
    private val logger = KotlinLogging.logger {}

    // Map of task ID to task state
    private val tasks = ConcurrentHashMap<String, AgentTask>()

    // Executor for running tasks asynchronously
    private val executor = Executors.newFixedThreadPool(5)

    /**
     * Start a new task.
     *
     * @param query The user query or task description
     * @param projectId The ID of the project
     * @param options Additional options for processing
     * @return The ID of the created task
     */
    fun startTask(
        query: String,
        projectId: ObjectId,
        options: Map<String, Any> = emptyMap(),
    ): String {
        logger.info { "Starting new task: ${query.take(50)}..." }

        // Create a new task ID
        val taskId = UUID.randomUUID().toString()

        // Create a new task
        val task =
            AgentTask(
                id = taskId,
                query = query,
                projectId = projectId,
                status = TaskStatus.PENDING,
                createdAt = LocalDateTime.now(),
                options = options,
            )

        // Store the task
        tasks[taskId] = task

        // Initialize working memory for the task
        workingMemory.addEntry(
            taskId = taskId,
            key = "task_query",
            value = query,
            type = MemoryEntryType.CONTEXT,
        )

        workingMemory.addEntry(
            taskId = taskId,
            key = "task_project_id",
            value = projectId.toString(),
            type = MemoryEntryType.CONTEXT,
        )

        // Submit the task for execution
        executor.submit {
            try {
                executeTask(taskId)
            } catch (e: Exception) {
                logger.error(e) { "Error executing task $taskId: ${e.message}" }

                // Update task status
                val currentTask = tasks[taskId]
                if (currentTask != null) {
                    tasks[taskId] =
                        currentTask.copy(
                            status = TaskStatus.FAILED,
                            error = e.message ?: "Unknown error",
                            completedAt = LocalDateTime.now(),
                        )
                }
            }
        }

        return taskId
    }

    /**
     * Get the current state of a task.
     *
     * @param taskId The ID of the task
     * @return The task state, or null if not found
     */
    fun getTaskState(taskId: String): AgentTask? = tasks[taskId]

    /**
     * Cancel a running task.
     *
     * @param taskId The ID of the task to cancel
     * @return True if the task was cancelled, false otherwise
     */
    fun cancelTask(taskId: String): Boolean {
        logger.info { "Cancelling task: $taskId" }

        val task = tasks[taskId] ?: return false

        // Only cancel if the task is still running
        if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PENDING) {
            tasks[taskId] =
                task.copy(
                    status = TaskStatus.CANCELLED,
                    completedAt = LocalDateTime.now(),
                )

            // Add cancellation note to working memory
            workingMemory.addEntry(
                taskId = taskId,
                key = "task_cancelled",
                value = "Task was cancelled by user",
                type = MemoryEntryType.NOTE,
            )

            return true
        }

        return false
    }

    /**
     * Execute a task.
     * This method implements the core orchestration cycle.
     *
     * @param taskId The ID of the task to execute
     */
    private fun executeTask(taskId: String) {
        logger.info { "Executing task: $taskId" }

        // Get the task
        val task =
            tasks[taskId] ?: run {
                logger.error { "Task not found: $taskId" }
                return
            }

        // Update task status
        tasks[taskId] = task.copy(status = TaskStatus.RUNNING)

        // Get the project
        val project =
            runBlocking {
                projectService.getProjectById(task.projectId)
            } ?: run {
                logger.error { "Project not found: ${task.projectId}" }
                tasks[taskId] =
                    task.copy(
                        status = TaskStatus.FAILED,
                        error = "Project not found: ${task.projectId}",
                        completedAt = LocalDateTime.now(),
                    )
                return
            }

        // Initialize result
        var result: String

        try {
            // Execute the orchestration cycle
            result = runBlocking { orchestrationCycle(taskId, task.query, project) }

            // Update task status
            tasks[taskId] =
                task.copy(
                    status = TaskStatus.COMPLETED,
                    result = result,
                    completedAt = LocalDateTime.now(),
                )

            // Record the task completion in memory
            recordTaskCompletion(taskId, task.query, result, project)
        } catch (e: Exception) {
            logger.error(e) { "Error in orchestration cycle for task $taskId: ${e.message}" }

            // Update task status
            tasks[taskId] =
                task.copy(
                    status = TaskStatus.FAILED,
                    error = e.message ?: "Unknown error",
                    completedAt = LocalDateTime.now(),
                )
        }
    }

    /**
     * Execute the orchestration cycle.
     * This method implements the four-step cycle: Analyze, Remember, Think, Act.
     *
     * @param taskId The ID of the task
     * @param query The user query
     * @param project The project
     * @return The final result of the task
     */
    private suspend fun orchestrationCycle(
        taskId: String,
        query: String,
        project: ProjectDocument,
    ): String {
        logger.info { "Starting orchestration cycle for task $taskId" }

        // Initialize cycle counter
        var cycleCount = 0
        var finalResult = ""
        var isTaskComplete = false

        // Continue the cycle until the task is complete or max cycles reached
        while (!isTaskComplete && cycleCount < MAX_CYCLES) {
            cycleCount++
            logger.info { "Orchestration cycle $cycleCount for task $taskId" }

            // Record cycle start in working memory
            workingMemory.addEntry(
                taskId = taskId,
                key = "cycle_start",
                value = "Starting cycle $cycleCount",
                type = MemoryEntryType.NOTE,
            )

            // Step 1: Analyze Task
            val taskAnalysis = analyzeTask(taskId, query, project)

            // Step 2: Remember (Retrieve relevant information)
            val relevantMemory = retrieveMemory(taskId, taskAnalysis, project)

            // Step 3: Think and Plan
            val plan = createPlan(taskId, taskAnalysis, relevantMemory, project)

            // Step 4: Act (Execute the plan)
            val actionResult = executePlan(taskId, plan, project)

            // Check if the task is complete
            val completionCheck = checkTaskCompletion(taskId, query, actionResult, project)
            isTaskComplete = completionCheck.isComplete

            // Update the final result
            finalResult = completionCheck.result

            // Record cycle end in working memory
            workingMemory.addEntry(
                taskId = taskId,
                key = "cycle_end",
                value = "Completed cycle $cycleCount. Task complete: $isTaskComplete",
                type = MemoryEntryType.NOTE,
            )

            // If the task is not complete, pause briefly before the next cycle
            if (!isTaskComplete) {
                Thread.sleep(1000) // 1 second pause
            }
        }

        // If we reached max cycles without completion, finalize with what we have
        if (!isTaskComplete) {
            logger.warn { "Reached maximum cycles ($MAX_CYCLES) for task $taskId without completion" }

            // Generate a final result based on what we've done so far
            finalResult = generateFinalResult(taskId, query, project)

            workingMemory.addEntry(
                taskId = taskId,
                key = "max_cycles_reached",
                value = "Reached maximum cycles ($MAX_CYCLES) without task completion",
                type = MemoryEntryType.ERROR,
            )
        }

        return finalResult
    }

    /**
     * Step 1: Analyze the task to understand what is being asked.
     *
     * @param taskId The ID of the task
     * @param query The user query
     * @param project The project
     * @return The task analysis
     */
    private fun analyzeTask(
        taskId: String,
        query: String,
        project: ProjectDocument,
    ): TaskAnalysis {
        logger.info { "Analyzing task: $taskId" }

        // Record in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "analyze_task",
            value = "Analyzing task: $query",
            type = MemoryEntryType.THOUGHT,
        )

        // Use LLM to analyze the task
        val analysisPrompt =
            """
            Analyze the following task and break it down into components:
            
            Task: $query
            
            Please provide:
            1. A clear description of what the task is asking for
            2. The key information needed to complete this task
            3. The types of actions that might be required
            4. Any potential challenges or ambiguities
            
            Format your response as a structured analysis.
            """.trimIndent()

        val analysisResponse = llmCoordinator.processQueryBlocking(analysisPrompt, "")

        // Extract components from the analysis
        val description = extractComponent(analysisResponse.answer, "description")
        val requiredInfo = extractComponent(analysisResponse.answer, "information")
        val requiredActions = extractComponent(analysisResponse.answer, "actions")
        val challenges = extractComponent(analysisResponse.answer, "challenges")

        // Create task analysis
        val analysis =
            TaskAnalysis(
                description = description,
                requiredInformation = requiredInfo,
                requiredActions = requiredActions,
                challenges = challenges,
                rawAnalysis = analysisResponse.answer,
            )

        // Store in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "task_analysis",
            value = analysis.rawAnalysis,
            type = MemoryEntryType.THOUGHT,
        )

        return analysis
    }

    /**
     * Step 2: Retrieve relevant information from memory using intelligent filtering.
     *
     * @param taskId The ID of the task
     * @param analysis The task analysis
     * @param project The project
     * @return The relevant memory items
     */
    private suspend fun retrieveMemory(
        taskId: String,
        analysis: TaskAnalysis,
        project: ProjectDocument,
    ): String {
        logger.info { "Retrieving memory for task: $taskId using intelligent filtering" }

        // Record in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "retrieve_memory",
            value = "Retrieving relevant information from memory using intelligent filtering",
            type = MemoryEntryType.THOUGHT,
        )

        // Generate search queries based on the analysis
        val searchQueries = generateSearchQueries(analysis)

        // Search memory for each query using intelligent filtering
        val memoryResults = StringBuilder()

        searchQueries.forEachIndexed { index, query ->
            logger.debug { "Searching memory with intelligent filtering for query $index: $query" }

            try {
                // Generate intelligent filter from the query
                val filter = filterGeneratorService.generateFilter(query, project.id)

                // Generate embedding for the query
                val queryEmbedding = embeddingService.generateQueryEmbedding(query)

                // Search with intelligent filtering
                val results = vectorStorageRepository.searchSimilar(queryEmbedding, filter, 3)

                if (results.isNotEmpty()) {
                    memoryResults.appendLine("=== Results for query: $query ===")
                    memoryResults.appendLine("Applied filter: $filter")

                    results.forEach { doc ->
                        memoryResults.appendLine("Type: ${doc.documentType}")
                        memoryResults.appendLine("Source: ${doc.ragSourceType}")
                        memoryResults.appendLine("Created: ${doc.createdAt}")
                        memoryResults.appendLine("Content: ${doc.pageContent}")
                        memoryResults.appendLine()
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error in intelligent filtering for query: $query" }
                // Fallback to basic search without filtering
                try {
                    val queryEmbedding = embeddingService.generateQueryEmbedding(query)
                    val basicFilter = mapOf("project" to project.id)
                    val results = vectorStorageRepository.searchSimilar(queryEmbedding, 3, basicFilter)

                    if (results.isNotEmpty()) {
                        memoryResults.appendLine("=== Basic results for query: $query ===")
                        results.forEach { doc ->
                            memoryResults.appendLine("Content: ${doc.pageContent}")
                            memoryResults.appendLine()
                        }
                    }
                } catch (fallbackError: Exception) {
                    logger.error(fallbackError) { "Fallback search also failed for query: $query" }
                }
            }
        }

        // Get recent decisions and plans using intelligent filtering
        try {
            val decisionFilter = RagDocumentFilter.byType(project.id, RagDocumentType.DECISION)
            val planFilter = RagDocumentFilter.byType(project.id, RagDocumentType.PLAN)

            // Use empty query for recent items (will get all items of the type)
            val emptyQuery = List(embeddingService.embeddingDimension) { 0.0f }

            val decisions =
                vectorStorageRepository
                    .searchSimilar(emptyQuery, decisionFilter, 5)
                    .sortedByDescending { it.createdAt }
                    .take(2)

            val plans =
                vectorStorageRepository
                    .searchSimilar(emptyQuery, planFilter, 5)
                    .sortedByDescending { it.createdAt }
                    .take(2)

            if (decisions.isNotEmpty()) {
                memoryResults.appendLine("=== Recent Decisions ===")
                decisions.forEach { doc ->
                    memoryResults.appendLine("Created: ${doc.createdAt}")
                    memoryResults.appendLine("Content: ${doc.pageContent}")
                    memoryResults.appendLine()
                }
            }

            if (plans.isNotEmpty()) {
                memoryResults.appendLine("=== Recent Plans ===")
                plans.forEach { doc ->
                    memoryResults.appendLine("Created: ${doc.createdAt}")
                    memoryResults.appendLine("Content: ${doc.pageContent}")
                    memoryResults.appendLine()
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error retrieving recent decisions and plans: ${e.message}" }
        }

        val memoryString = memoryResults.toString()

        // Store in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "memory_results",
            value = if (memoryString.isBlank()) "No relevant memory items found" else memoryString,
            type = MemoryEntryType.CONTEXT,
        )

        logger.debug { "Retrieved memory results: ${memoryString.length} characters" }
        return memoryString
    }

    /**
     * Step 3: Think and create a plan.
     *
     * @param taskId The ID of the task
     * @param analysis The task analysis
     * @param memory The relevant memory items
     * @param project The project
     * @return The plan
     */
    private fun createPlan(
        taskId: String,
        analysis: TaskAnalysis,
        memory: String,
        project: ProjectDocument,
    ): TaskPlan {
        logger.info { "Creating plan for task: $taskId" }

        // Record in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "create_plan",
            value = "Creating a plan based on task analysis and memory",
            type = MemoryEntryType.THOUGHT,
        )

        // Get available tools
        val availableTools =
            agentToolbox
                .getAllTools()
                .joinToString("\n") { "- ${it.name}: ${it.description}" }

        // Use LLM to create a plan
        val planPrompt =
            """
            Based on the task analysis and relevant memory, create a detailed plan to complete the task.
            
            Task Analysis:
            ${analysis.rawAnalysis}
            
            Relevant Memory:
            ${if (memory.isBlank()) "No relevant memory items found" else memory}
            
            Available Tools:
            $availableTools
            
            Please create a step-by-step plan that:
            1. Breaks down the task into clear, sequential steps
            2. Specifies which tools to use for each step
            3. Includes any necessary parameters for the tools
            4. Accounts for potential challenges identified in the analysis
            
            Format your response as a numbered list of steps, with each step including:
            - Description of the action
            - Tool to use (if applicable)
            - Parameters for the tool (if applicable)
            """.trimIndent()

        val planResponse = llmCoordinator.processQueryBlocking(planPrompt, "")

        // Parse the plan into steps
        val steps = parsePlanSteps(planResponse.answer)

        // Create task plan
        val plan =
            TaskPlan(
                steps = steps,
                rawPlan = planResponse.answer,
            )

        // Store in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "task_plan",
            value = plan.rawPlan,
            type = MemoryEntryType.PLAN,
        )

        return plan
    }

    /**
     * Step 4: Execute the plan.
     *
     * @param taskId The ID of the task
     * @param plan The task plan
     * @param project The project
     * @return The result of executing the plan
     */
    private fun executePlan(
        taskId: String,
        plan: TaskPlan,
        project: ProjectDocument,
    ): String {
        logger.info { "Executing plan for task: $taskId" }

        // Record in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "execute_plan",
            value = "Executing the plan with ${plan.steps.size} steps",
            type = MemoryEntryType.THOUGHT,
        )

        val results = StringBuilder()

        // Execute each step in the plan
        plan.steps.forEachIndexed { index, step ->
            val stepNumber = index + 1
            logger.info { "Executing step $stepNumber: ${step.description}" }

            // Record step start in working memory
            workingMemory.addEntry(
                taskId = taskId,
                key = "step_start_$stepNumber",
                value = "Starting step $stepNumber: ${step.description}",
                type = MemoryEntryType.NOTE,
            )

            val stepResult =
                if (step.tool != null) {
                    // Execute the tool
                    val toolResult =
                        agentToolbox.executeTool(
                            name = step.tool,
                            params = step.parameters,
                            taskId = taskId,
                        )

                    if (toolResult.success) {
                        toolResult.output
                    } else {
                        "Error executing tool ${step.tool}: ${toolResult.error}"
                    }
                } else {
                    // No tool specified, use LLM to perform the step
                    val stepPrompt =
                        """
                        Perform the following step in the task:
                        
                        Step: ${step.description}
                        
                        Please provide the result of performing this step.
                        """.trimIndent()

                    val stepResponse = llmCoordinator.processQueryBlocking(stepPrompt, "")
                    stepResponse.answer
                }

            // Record step result in working memory
            workingMemory.addEntry(
                taskId = taskId,
                key = "step_result_$stepNumber",
                value = stepResult,
                type = MemoryEntryType.RESULT,
            )

            // Add to results
            results.appendLine("Step $stepNumber: ${step.description}")
            results.appendLine("Result: $stepResult")
            results.appendLine()
        }

        val planResults = results.toString()

        // Store in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "plan_results",
            value = planResults,
            type = MemoryEntryType.RESULT,
        )

        return planResults
    }

    /**
     * Check if the task is complete.
     *
     * @param taskId The ID of the task
     * @param query The original query
     * @param actionResult The result of executing the plan
     * @param project The project
     * @return The completion check result
     */
    private fun checkTaskCompletion(
        taskId: String,
        query: String,
        actionResult: String,
        project: ProjectDocument,
    ): CompletionCheck {
        logger.info { "Checking task completion: $taskId" }

        // Record in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "check_completion",
            value = "Checking if the task is complete",
            type = MemoryEntryType.THOUGHT,
        )

        // Get the working memory summary
        val memorySummary = workingMemory.getSummary(taskId)

        // Use LLM to check if the task is complete
        val completionPrompt =
            """
            Determine if the following task has been completed based on the actions taken and results obtained.
            
            Original Task: $query
            
            Action Results:
            $actionResult
            
            Working Memory Summary:
            $memorySummary
            
            Please analyze whether the task has been fully completed. Consider:
            1. Were all required actions performed successfully?
            2. Were all the objectives of the task met?
            3. Is there any part of the task that still needs to be addressed?
            
            Provide your assessment in the following format:
            
            COMPLETION: [YES/NO]
            REASON: [Your detailed reasoning]
            NEXT STEPS: [If not complete, what needs to be done next]
            FINAL RESULT: [A concise summary of the task result]
            """.trimIndent()

        val completionResponse = llmCoordinator.processQueryBlocking(completionPrompt, "")

        // Parse the completion check
        val isComplete = completionResponse.answer.contains("COMPLETION: YES", ignoreCase = true)
        val reason = extractComponent(completionResponse.answer, "REASON")
        val nextSteps = extractComponent(completionResponse.answer, "NEXT STEPS")
        val result = extractComponent(completionResponse.answer, "FINAL RESULT")

        // Store in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "completion_check",
            value = completionResponse.answer,
            type = MemoryEntryType.DECISION,
        )

        return CompletionCheck(
            isComplete = isComplete,
            reason = reason,
            nextSteps = nextSteps,
            result = result,
            rawCheck = completionResponse.answer,
        )
    }

    /**
     * Generate a final result for the task.
     *
     * @param taskId The ID of the task
     * @param query The original query
     * @param project The project
     * @return The final result
     */
    private fun generateFinalResult(
        taskId: String,
        query: String,
        project: ProjectDocument,
    ): String {
        logger.info { "Generating final result for task: $taskId" }

        // Record in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "generate_final_result",
            value = "Generating final result for the task",
            type = MemoryEntryType.THOUGHT,
        )

        // Get the working memory summary
        val memorySummary = workingMemory.getSummary(taskId)

        // Use LLM to generate a final result
        val resultPrompt =
            """
            Generate a comprehensive final result for the following task based on all the work done so far.
            
            Original Task: $query
            
            Working Memory Summary:
            $memorySummary
            
            Please provide a well-structured final result that:
            1. Summarizes what was done to address the task
            2. Presents the key findings or outcomes
            3. Addresses any limitations or remaining issues
            4. Provides a clear conclusion
            
            Format your response as a comprehensive final result.
            """.trimIndent()

        val resultResponse = llmCoordinator.processQueryBlocking(resultPrompt, "")

        // Store in working memory
        workingMemory.addEntry(
            taskId = taskId,
            key = "final_result",
            value = resultResponse.answer,
            type = MemoryEntryType.RESULT,
        )

        return resultResponse.answer
    }

    /**
     * Record the task completion in memory.
     *
     * @param taskId The ID of the task
     * @param query The original query
     * @param result The final result
     * @param project The project
     */
    private fun recordTaskCompletion(
        taskId: String,
        query: String,
        result: String,
        project: ProjectDocument,
    ) {
        logger.info { "Recording task completion in memory: $taskId" }

        try {
            // Create a memory item for the task
            "Task Completion: ${query.take(50)}..."
            """
            Task: $query
            
            Result:
            $result
            
            Task ID: $taskId
            Completed At: ${LocalDateTime.now()}
            """.trimIndent()

// TODO implemnt stoer to RAG ?
            logger.info { "Task completion recorded in memory: $taskId" }
        } catch (e: Exception) {
            logger.error(e) { "Error recording task completion in memory: ${e.message}" }
        }
    }

    /**
     * Generate search queries based on the task analysis.
     *
     * @param analysis The task analysis
     * @return A list of search queries
     */
    private fun generateSearchQueries(analysis: TaskAnalysis): List<String> {
        // Extract key terms from the analysis
        val queries = mutableListOf<String>()

        // Add the description as a query
        if (analysis.description.isNotBlank()) {
            queries.add(analysis.description)
        }

        // Add required information as queries
        if (analysis.requiredInformation.isNotBlank()) {
            val infoItems =
                analysis.requiredInformation
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .map { it.trim() }

            queries.addAll(infoItems)
        }

        // Limit the number of queries
        return queries.take(3)
    }

    /**
     * Parse plan steps from the raw plan text.
     *
     * @param rawPlan The raw plan text
     * @return A list of plan steps
     */
    private fun parsePlanSteps(rawPlan: String): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()

        // Simple parsing logic - in a real implementation, you would use more robust parsing
        val stepRegex =
            """(?:Step\s*(\d+)[:.]\s*|(\d+)[:.]\s*)(.+?)(?=(?:Step\s*\d+[:.]\s*|\d+[:.]\s*|$))""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val matches = stepRegex.findAll(rawPlan)

        for (match in matches) {
            val stepText = match.groupValues[3].trim()

            // Extract tool and parameters if present
            val toolMatch = """Tool:\s*([a-z_]+)""".toRegex(RegexOption.IGNORE_CASE).find(stepText)
            val tool = toolMatch?.groupValues?.get(1)?.trim()

            // Extract parameters
            val params = mutableMapOf<String, Any>()
            if (tool != null) {
                val paramMatches =
                    """([a-z_]+):\s*"([^"]+)"|([a-z_]+):\s*([^,\s]+)"""
                        .toRegex(RegexOption.IGNORE_CASE)
                        .findAll(stepText)
                for (paramMatch in paramMatches) {
                    val key = (paramMatch.groupValues[1].takeIf { it.isNotBlank() } ?: paramMatch.groupValues[3]).trim()
                    val value =
                        (paramMatch.groupValues[2].takeIf { it.isNotBlank() } ?: paramMatch.groupValues[4]).trim()

                    if (key != "tool" && key.isNotBlank() && value.isNotBlank()) {
                        params[key] = value
                    }
                }
            }

            steps.add(
                PlanStep(
                    description = stepText,
                    tool = tool,
                    parameters = params,
                ),
            )
        }

        return steps
    }

    /**
     * Extract a component from the analysis text.
     *
     * @param text The text to extract from
     * @param component The name of the component to extract
     * @return The extracted component
     */
    private fun extractComponent(
        text: String,
        component: String,
    ): String {
        val pattern =
            "(?i)\\b$component\\b[:\\s]+(.*?)(?=\\b(?:description|information|actions|challenges|reason|next steps|final result)\\b|$)"
                .toRegex(
                    RegexOption.DOT_MATCHES_ALL,
                )
        val match = pattern.find(text)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    /**
     * Clean up expired tasks periodically.
     */
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    fun cleanupExpiredTasks() {
        logger.info { "Cleaning up expired tasks" }

        val now = LocalDateTime.now()
        val expiredTasks =
            tasks.entries
                .filter { (_, task) ->
                    task.status.isTerminal() &&
                        task.completedAt?.plusDays(7)?.isBefore(now) ?: false
                }.map { it.key }

        expiredTasks.forEach { taskId ->
            logger.info { "Removing expired task: $taskId" }
            tasks.remove(taskId)
            workingMemory.clearTask(taskId)
        }

        // Also clean up expired working memory
        workingMemory.clearExpiredTasks()
    }

    companion object {
        // Maximum number of cycles for a task
        private const val MAX_CYCLES = 5
    }
}

/**
 * Status of an agent task.
 */
enum class TaskStatus {
    PENDING, // Task is waiting to be executed
    RUNNING, // Task is currently running
    COMPLETED, // Task completed successfully
    FAILED, // Task failed
    CANCELLED, // Task was cancelled
    ;

    /**
     * Check if the status is terminal (i.e., the task is no longer running).
     */
    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED || this == CANCELLED
}

/**
 * Represents an agent task.
 */
data class AgentTask(
    val id: String,
    val query: String,
    val projectId: ObjectId,
    val status: TaskStatus,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime? = null,
    val result: String? = null,
    val error: String? = null,
    val options: Map<String, Any> = emptyMap(),
)

/**
 * Analysis of a task.
 */
data class TaskAnalysis(
    val description: String,
    val requiredInformation: String,
    val requiredActions: String,
    val challenges: String,
    val rawAnalysis: String,
)

/**
 * A step in a task plan.
 */
data class PlanStep(
    val description: String,
    val tool: String? = null,
    val parameters: Map<String, Any> = emptyMap(),
)

/**
 * A plan for completing a task.
 */
data class TaskPlan(
    val steps: List<PlanStep>,
    val rawPlan: String,
)

/**
 * Result of checking if a task is complete.
 */
data class CompletionCheck(
    val isComplete: Boolean,
    val reason: String,
    val nextSteps: String,
    val result: String,
    val rawCheck: String,
)
