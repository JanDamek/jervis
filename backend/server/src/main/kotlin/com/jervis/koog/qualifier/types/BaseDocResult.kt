package com.jervis.koog.qualifier.types

import kotlinx.serialization.Serializable

/**
 * Result z vytvoření base document node.
 * Nahrazuje runBlocking inline kód.
 */
@Serializable
data class BaseDocResult(
    /**
     * Úspěšnost operace
     */
    val success: Boolean,
    /**
     * ID vytvořeného RAG chunku pro base document
     */
    val chunkId: String,
    /**
     * Key base document node
     */
    val nodeKey: String,
    /**
     * Chybová zpráva (pokud success = false)
     */
    val errorMessage: String? = null,
)
