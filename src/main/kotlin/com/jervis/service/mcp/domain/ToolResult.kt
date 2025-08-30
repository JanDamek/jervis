package com.jervis.service.mcp.domain

// Kotlin
sealed interface ToolResult {
    val output: String

    data class Ok(
        override val output: String,
    ) : ToolResult

    data class Error(
        override val output: String,
        val errorMessage: String? = null,
    ) : ToolResult

    data class Ask(
        override val output: String,
    ) : ToolResult

    fun render(): String =
        when (this) {
            is Ok -> "OK: ${this.output}"
            is Error -> "ERROR: ${this.errorMessage ?: "Unknown"}"
            is Ask -> "ASK: ${this.output}"
        }

    companion object {
        fun ok(output: String): ToolResult = Ok(output)

        fun error(
            output: String,
            message: String? = null,
        ): ToolResult = Error(output, message)

        fun ask(output: String): ToolResult = Ask(output)
    }
}
