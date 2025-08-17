package com.jervis.util

import kotlin.math.sqrt

/**
 * Utility functions for embedding operations
 */
object EmbeddingUtils {
    
    /**
     * L2 normalization which returns a new FloatArray instead of modifying immutable List
     */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (x in vector) sum += x * x
        val magnitude = if (sum > 0f) sqrt(sum) else 1f
        return FloatArray(vector.size) { i -> vector[i] / magnitude }
    }
    
    /**
     * L2 normalization for List<Float> returning FloatArray
     */
    fun l2Normalize(vector: List<Float>): FloatArray {
        return l2Normalize(vector.toFloatArray())
    }
    
    /**
     * Prefixing with guard against double prefixing
     */
    fun applyPrefix(text: String, prefix: String): String =
        if (text.startsWith(prefix)) text else prefix + text
    
    /**
     * Calculate cosine similarity between two vectors
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have same dimensions" }
        
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        return if (normA == 0f || normB == 0f) 0f else dotProduct / (sqrt(normA) * sqrt(normB))
    }
    
    /**
     * Batch normalize vectors
     */
    fun batchL2Normalize(vectors: List<FloatArray>): List<FloatArray> {
        return vectors.map { l2Normalize(it) }
    }
}