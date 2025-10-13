package com.jervis.domain.rag

/**
 * Types of code symbols that can be indexed
 */
enum class SymbolType {
    /** Method or function */
    METHOD,

    /** Class or type definition */
    CLASS,

    /** Interface definition */
    INTERFACE,

    /** Variable declaration */
    VARIABLE,

    /** Field in a class */
    FIELD,

    /** Function parameter */
    PARAMETER,
}
