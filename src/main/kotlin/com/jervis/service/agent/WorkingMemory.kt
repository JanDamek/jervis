package com.jervis.service.agent

import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID
import mu.KotlinLogging

/**
 * Working Memory (Short-term Memory) for the Agent.
 * 
 * This service provides a temporary storage for information during task execution.
 * It acts as a "working notebook" where the agent can store intermediate results,
 * thoughts, and other temporary information needed to complete a task.
 * 
 * Unlike the long-term memory (RAG services), this memory is task-specific and
 * is cleared when the task is completed or after a certain period of inactivity.
 */
@Service
class WorkingMemory {
    private val logger = KotlinLogging.logger {}
    
    // Map of task ID to memory entries
    private val memories = mutableMapOf<String, MutableList<MemoryEntry>>()
    
    // Map of task ID to last access time
    private val lastAccess = mutableMapOf<String, LocalDateTime>()
    
    // Default expiration time for memories (30 minutes)
    private val defaultExpirationMinutes = 30L
    
    /**
     * Add an entry to the working memory for a specific task.
     *
     * @param taskId The ID of the task
     * @param key The key for the memory entry
     * @param value The value to store
     * @param type The type of memory entry
     * @return The created memory entry
     */
    fun addEntry(
        taskId: String,
        key: String,
        value: String,
        type: MemoryEntryType = MemoryEntryType.NOTE
    ): MemoryEntry {
        logger.debug { "Adding entry to working memory for task $taskId: $key" }
        
        // Create a new memory entry
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            key = key,
            value = value,
            type = type,
            timestamp = LocalDateTime.now()
        )
        
        // Initialize the task's memory list if it doesn't exist
        if (!memories.containsKey(taskId)) {
            memories[taskId] = mutableListOf()
        }
        
        // Add the entry to the task's memory list
        memories[taskId]?.add(entry)
        
        // Update the last access time
        lastAccess[taskId] = LocalDateTime.now()
        
        return entry
    }
    
    /**
     * Get all entries for a specific task.
     *
     * @param taskId The ID of the task
     * @return A list of memory entries for the task
     */
    fun getEntries(taskId: String): List<MemoryEntry> {
        logger.debug { "Getting all entries from working memory for task $taskId" }
        
        // Update the last access time
        lastAccess[taskId] = LocalDateTime.now()
        
        return memories[taskId]?.toList() ?: emptyList()
    }
    
    /**
     * Get entries of a specific type for a task.
     *
     * @param taskId The ID of the task
     * @param type The type of memory entries to get
     * @return A list of memory entries of the specified type
     */
    fun getEntriesByType(taskId: String, type: MemoryEntryType): List<MemoryEntry> {
        logger.debug { "Getting entries of type $type from working memory for task $taskId" }
        
        // Update the last access time
        lastAccess[taskId] = LocalDateTime.now()
        
        return memories[taskId]?.filter { it.type == type }?.toList() ?: emptyList()
    }
    
    /**
     * Get entries with a specific key for a task.
     *
     * @param taskId The ID of the task
     * @param key The key to search for
     * @return A list of memory entries with the specified key
     */
    fun getEntriesByKey(taskId: String, key: String): List<MemoryEntry> {
        logger.debug { "Getting entries with key $key from working memory for task $taskId" }
        
        // Update the last access time
        lastAccess[taskId] = LocalDateTime.now()
        
        return memories[taskId]?.filter { it.key == key }?.toList() ?: emptyList()
    }
    
    /**
     * Get the most recent entry with a specific key for a task.
     *
     * @param taskId The ID of the task
     * @param key The key to search for
     * @return The most recent memory entry with the specified key, or null if not found
     */
    fun getLatestEntryByKey(taskId: String, key: String): MemoryEntry? {
        logger.debug { "Getting latest entry with key $key from working memory for task $taskId" }
        
        // Update the last access time
        lastAccess[taskId] = LocalDateTime.now()
        
        return memories[taskId]
            ?.filter { it.key == key }
            ?.maxByOrNull { it.timestamp }
    }
    
    /**
     * Update an existing entry in the working memory.
     *
     * @param taskId The ID of the task
     * @param entryId The ID of the entry to update
     * @param value The new value for the entry
     * @return The updated memory entry, or null if not found
     */
    fun updateEntry(taskId: String, entryId: String, value: String): MemoryEntry? {
        logger.debug { "Updating entry $entryId in working memory for task $taskId" }
        
        // Find the entry
        val entryIndex = memories[taskId]?.indexOfFirst { it.id == entryId } ?: -1
        if (entryIndex == -1) {
            logger.warn { "Entry $entryId not found in working memory for task $taskId" }
            return null
        }
        
        // Update the entry
        val oldEntry = memories[taskId]!![entryIndex]
        val newEntry = oldEntry.copy(value = value, timestamp = LocalDateTime.now())
        memories[taskId]!![entryIndex] = newEntry
        
        // Update the last access time
        lastAccess[taskId] = LocalDateTime.now()
        
        return newEntry
    }
    
    /**
     * Remove an entry from the working memory.
     *
     * @param taskId The ID of the task
     * @param entryId The ID of the entry to remove
     * @return True if the entry was removed, false otherwise
     */
    fun removeEntry(taskId: String, entryId: String): Boolean {
        logger.debug { "Removing entry $entryId from working memory for task $taskId" }
        
        // Update the last access time
        lastAccess[taskId] = LocalDateTime.now()
        
        return memories[taskId]?.removeIf { it.id == entryId } ?: false
    }
    
    /**
     * Clear all entries for a specific task.
     *
     * @param taskId The ID of the task
     */
    fun clearTask(taskId: String) {
        logger.debug { "Clearing all entries from working memory for task $taskId" }
        
        memories.remove(taskId)
        lastAccess.remove(taskId)
    }
    
    /**
     * Clear expired tasks from memory.
     * This method removes tasks that haven't been accessed for a certain period.
     *
     * @param expirationMinutes The number of minutes after which a task is considered expired
     * @return The number of tasks cleared
     */
    fun clearExpiredTasks(expirationMinutes: Long = defaultExpirationMinutes): Int {
        logger.debug { "Clearing expired tasks from working memory" }
        
        val now = LocalDateTime.now()
        val expiredTaskIds = lastAccess.entries
            .filter { now.minusMinutes(expirationMinutes).isAfter(it.value) }
            .map { it.key }
        
        expiredTaskIds.forEach { taskId ->
            memories.remove(taskId)
            lastAccess.remove(taskId)
        }
        
        return expiredTaskIds.size
    }
    
    /**
     * Get a summary of the working memory for a task.
     * This method returns a formatted string containing all entries for the task.
     *
     * @param taskId The ID of the task
     * @return A formatted string summary of the working memory
     */
    fun getSummary(taskId: String): String {
        logger.debug { "Getting summary of working memory for task $taskId" }
        
        // Update the last access time
        lastAccess[taskId] = LocalDateTime.now()
        
        val entries = memories[taskId] ?: return "No entries found for task $taskId"
        
        val summary = StringBuilder()
        summary.appendLine("Working Memory Summary for Task $taskId:")
        summary.appendLine("Total Entries: ${entries.size}")
        summary.appendLine()
        
        // Group entries by type
        val entriesByType = entries.groupBy { it.type }
        
        entriesByType.forEach { (type, typeEntries) ->
            summary.appendLine("== ${type.name} Entries (${typeEntries.size}) ==")
            typeEntries.sortedByDescending { it.timestamp }.forEach { entry ->
                summary.appendLine("- ${entry.key}: ${entry.value}")
            }
            summary.appendLine()
        }
        
        return summary.toString()
    }
}

/**
 * Types of memory entries in the working memory.
 */
enum class MemoryEntryType {
    NOTE,       // General notes or observations
    THOUGHT,    // Agent's thoughts or reasoning
    PLAN,       // Planning steps or goals
    RESULT,     // Results of actions or operations
    CONTEXT,    // Contextual information
    ERROR,      // Error information
    DECISION,   // Decisions made by the agent
}

/**
 * A single entry in the working memory.
 */
data class MemoryEntry(
    val id: String,
    val key: String,
    val value: String,
    val type: MemoryEntryType,
    val timestamp: LocalDateTime
)