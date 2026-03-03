package com.jervis.ui.chat

import com.jervis.dto.ui.ChatMessage

/**
 * Groups flat chat messages into display items: standalone messages and threaded cards.
 *
 * Threading logic:
 * - BACKGROUND_RESULT with taskId → thread header
 * - Messages with contextTaskId → thread replies (matched to header by taskId)
 * - Everything else → standalone
 * - Orphan replies (contextTaskId but no matching header) → standalone fallback
 * - Result sorted by last activity timestamp
 */
fun groupIntoDisplayItems(
    messages: List<ChatMessage>,
    lastSeenTimestamps: Map<String, String>,
): List<ChatDisplayItem> {
    val taskHeaders = mutableMapOf<String, ChatMessage>()
    val taskReplies = mutableMapOf<String, MutableList<ChatMessage>>()
    val standalones = mutableListOf<ChatMessage>()

    for (msg in messages) {
        when {
            msg.messageType == ChatMessage.MessageType.BACKGROUND_RESULT -> {
                val tid = msg.metadata["taskId"]
                if (tid != null) {
                    taskHeaders[tid] = msg
                } else {
                    standalones.add(msg)
                }
            }
            msg.metadata["contextTaskId"] != null -> {
                val tid = msg.metadata["contextTaskId"]!!
                taskReplies.getOrPut(tid) { mutableListOf() }.add(msg)
            }
            else -> standalones.add(msg)
        }
    }

    val items = mutableListOf<ChatDisplayItem>()

    // Build threads
    for ((taskId, header) in taskHeaders) {
        val replies = taskReplies.remove(taskId) ?: emptyList()
        val lastSeen = lastSeenTimestamps[taskId]
        val unread = if (lastSeen != null) {
            replies.count { (it.timestamp ?: "") > lastSeen }
        } else {
            replies.size
        }
        items.add(ChatDisplayItem.Thread(taskId, header, replies, unread))
    }

    // Orphan replies (contextTaskId but no matching header) → standalone
    for ((_, orphans) in taskReplies) {
        orphans.forEach { items.add(ChatDisplayItem.Standalone(it)) }
    }

    // Standalone messages
    standalones.forEach { items.add(ChatDisplayItem.Standalone(it)) }

    // Sort by last activity
    return items.sortedBy { it.sortTimestamp ?: "" }
}
