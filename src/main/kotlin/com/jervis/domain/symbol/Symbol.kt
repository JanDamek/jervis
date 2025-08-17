package com.jervis.domain.symbol

/**
 * Data class representing a code symbol (class, method, function, etc.)
 */
data class Symbol(
    val name: String,
    val qualifiedName: String,
    val kind: SymbolKind,
    val parent: String? = null,
    val startLine: Int = 0,
    val endLine: Int = 0,
    val documentation: String? = null,
    val modifiers: List<String> = emptyList(),
    val parameters: List<SymbolParameter> = emptyList(),
    val returnType: String? = null
)

/**
 * Data class representing a parameter of a symbol (for methods/functions)
 */
data class SymbolParameter(
    val name: String,
    val type: String,
    val defaultValue: String? = null
)