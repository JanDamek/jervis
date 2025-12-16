package com.jervis.koog.qualifier.types

import kotlinx.serialization.Serializable

/**
 * Typed result z GraphRagTools.storeKnowledge() tool.
 * Nahrazuje string parsing regexem.
 */
@Serializable
data class StoreKnowledgeResult(
    /**
     * Úspěšnost operace
     */
    val success: Boolean,

    /**
     * ID vytvořeného RAG chunku
     */
    val chunkId: String,

    /**
     * Hlavní node key pro tento chunk (např. "concept_neural_networks")
     */
    val mainNodeKey: String,

    /**
     * Počet vytvořených graph nodes
     */
    val nodesCreated: Int,

    /**
     * Počet vytvořených graph edges
     */
    val edgesCreated: Int,

    /**
     * Chybová zpráva (pokud success = false)
     */
    val errorMessage: String? = null
)
