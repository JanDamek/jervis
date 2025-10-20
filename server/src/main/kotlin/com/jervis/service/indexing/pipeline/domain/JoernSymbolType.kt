package com.jervis.service.indexing.pipeline.domain

/**
 * Data classes for pipeline-based streaming processing.
 * These classes support the flow of data through the indexing pipeline stages.
 */

enum class JoernSymbolType {
    CLASS,
    METHOD,
    FUNCTION,
    VARIABLE,
    CALL,
    IMPORT,
    FIELD,
    PARAMETER,
    FILE,
    PACKAGE,
    MODULE,
}
