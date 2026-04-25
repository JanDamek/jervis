package com.jervis.agentjob

import com.jervis.dto.agentjob.AgentNarrativeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Parser for `claude --output-format stream-json` JSONL files written by
 * `entrypoint-coding.sh` (Fáze I). Maps raw Claude CLI events into the
 * structured `AgentNarrativeEvent` shape consumed by the sidebar
 * Background detail panel (Fáze K).
 *
 * Stream-json schema observed in the wild:
 * ```
 * {"type":"system","subtype":"init",...}
 * {"type":"assistant","message":{"content":[{"type":"text","text":"…"}]}}
 * {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{...}}]}}
 * {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"…","content":"…","is_error":false}]}}
 * {"type":"result","subtype":"success",...}
 * ```
 *
 * Multi-block messages are flattened — one input line can yield
 * several output events when Claude emits text + tool_use in the same
 * `message.content` array.
 */
@Component
class ClaudeStreamJsonlParser {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse a single JSONL line into zero, one, or more
     * `AgentNarrativeEvent`s. Returns empty list on parse failure or
     * when the line carries no observable content (e.g. heartbeat).
     */
    fun parseLine(line: String): List<AgentNarrativeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val now = Instant.now().toString()
        val root = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            logger.warn { "ClaudeStreamJsonlParser | failed to parse line: ${e.message} | snippet=${trimmed.take(120)}" }
            return emptyList()
        }
        val type = root["type"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "assistant" -> parseAssistant(root, now)
            "user" -> parseUserToolResult(root, now)
            "system" -> listOf(
                AgentNarrativeEvent.SystemEvent(
                    timestamp = now,
                    kind = root["subtype"]?.jsonPrimitive?.contentOrNull ?: "system",
                    content = trimmed.take(500),
                ),
            )
            "result" -> listOf(
                AgentNarrativeEvent.SystemEvent(
                    timestamp = now,
                    kind = "result/${root["subtype"]?.jsonPrimitive?.contentOrNull ?: "?"}",
                    content = root["result"]?.jsonPrimitive?.contentOrNull?.take(500) ?: "",
                ),
            )
            else -> emptyList()
        }
    }

    /** Parse a whole `.jsonl` file body. Suitable for terminal-job replay. */
    fun parseAll(text: String): List<AgentNarrativeEvent> =
        text.lineSequence().flatMap { parseLine(it).asSequence() }.toList()

    private fun parseAssistant(root: JsonObject, ts: String): List<AgentNarrativeEvent> {
        val message = root["message"]?.jsonObject ?: return emptyList()
        val content = message["content"] ?: return emptyList()
        val blocks = runCatching { content.jsonArray }.getOrNull() ?: return emptyList()
        return blocks.mapNotNull { blockEl ->
            val block = blockEl.jsonObject
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (text.isBlank()) null
                    else AgentNarrativeEvent.AssistantText(timestamp = ts, text = text)
                }
                "tool_use" -> {
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    val input = block["input"] ?: return@mapNotNull null
                    AgentNarrativeEvent.ToolUse(
                        timestamp = ts,
                        toolName = name,
                        paramsPreview = summarise(input.toString()),
                    )
                }
                else -> null
            }
        }
    }

    private fun parseUserToolResult(root: JsonObject, ts: String): List<AgentNarrativeEvent> {
        val message = root["message"]?.jsonObject ?: return emptyList()
        val content = message["content"] ?: return emptyList()
        val blocks = runCatching { content.jsonArray }.getOrNull() ?: return emptyList()
        return blocks.mapNotNull { blockEl ->
            val block = blockEl.jsonObject
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "tool_result" -> {
                    val output = block["content"]?.let { c ->
                        // tool_result.content is sometimes a plain string, sometimes
                        // an array of {type:"text", text:"..."} blocks.
                        val asString = runCatching { c.jsonPrimitive.content }.getOrNull()
                        if (asString != null) asString
                        else runCatching { c.jsonArray.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() } }
                            .getOrDefault(c.toString())
                    }.orEmpty()
                    val isError = block["is_error"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                    AgentNarrativeEvent.ToolResult(
                        timestamp = ts,
                        // tool_use_id correlates with the prior ToolUse — we don't
                        // dereference it here (UI groups by sequential pairing).
                        toolName = "result",
                        isError = isError,
                        outputPreview = summarise(output),
                    )
                }
                else -> null
            }
        }
    }

    /** Truncate aggressive payloads — the UI doesn't render full file dumps. */
    private fun summarise(s: String): String {
        val collapsed = s.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= 200) collapsed else collapsed.take(200) + "…"
    }
}
