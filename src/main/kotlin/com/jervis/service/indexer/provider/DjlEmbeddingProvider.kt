package com.jervis.service.indexer.provider

import ai.djl.inference.Predictor
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.ndarray.NDManager
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext
import mu.KotlinLogging
import java.io.Closeable
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.sqrt

/**
 * Thread-safe DJL embedding provider with batch processing and predictor pool
 */
class DjlEmbeddingProvider(
    private val criteria: Criteria<List<String>, List<FloatArray>>,
    poolSize: Int = 4
) : EmbeddingProvider, Closeable {
    
    private val logger = KotlinLogging.logger {}
    private val model = criteria.loadModel()
    private val pool = ArrayDeque<Predictor<List<String>, List<FloatArray>>>(poolSize)
    private val lock = ReentrantLock()
    
    init {
        repeat(poolSize) { 
            pool.add(model.newPredictor(BatchEmbeddingTranslator())) 
        }
        logger.info { "Initialized DJL provider with $poolSize predictors" }
    }

    override fun getDimension(): Int = 768 // or dynamically from model

    override fun predict(text: String): List<Float> {
        return predict(listOf(text)).first()
    }

    override fun predict(texts: List<String>): List<List<Float>> {
        val predictor = acquirePredictor()
        return try {
            val embeddings = predictor.predict(texts)
            embeddings.map { it.toList() }
        } catch (e: Exception) {
            logger.error(e) { "DJL prediction failed" }
            throw e
        } finally {
            releasePredictor(predictor)
        }
    }
    
    fun predictBatch(texts: List<String>): List<FloatArray> {
        val predictor = acquirePredictor()
        return try {
            predictor.predict(texts)
        } catch (e: Exception) {
            logger.error(e) { "DJL batch prediction failed" }
            throw e
        } finally {
            releasePredictor(predictor)
        }
    }

    private fun acquirePredictor(): Predictor<List<String>, List<FloatArray>> {
        lock.lock()
        return try {
            while (pool.isEmpty()) {
                lock.unlock()
                Thread.yield()
                lock.lock()
            }
            pool.removeFirst()
        } finally {
            lock.unlock()
        }
    }

    private fun releasePredictor(predictor: Predictor<List<String>, List<FloatArray>>) {
        lock.lock()
        try {
            pool.addLast(predictor)
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        lock.lock()
        try {
            pool.forEach { it.close() }
            pool.clear()
        } finally {
            lock.unlock()
        }
        model.close()
    }
}

/**
 * Custom batch translator for DJL
 */
class BatchEmbeddingTranslator : Translator<List<String>, List<FloatArray>> {
    
    override fun processInput(ctx: TranslatorContext, input: List<String>): NDList {
        val manager = ctx.ndManager
        val tokenizedInputs = input.map { tokenizeText(it, manager) }
        return NDList(tokenizedInputs)
    }
    
    override fun processOutput(ctx: TranslatorContext, list: NDList): List<FloatArray> {
        return list.map { ndArray ->
            val floatArray = ndArray.toFloatArray()
            l2Normalize(floatArray) // L2 normalization at output
        }
    }
    
    private fun tokenizeText(text: String, manager: NDManager): NDArray {
        // Implementation depends on specific model (BERT, E5, etc.)
        // For now, create a simple representation
        return manager.create(text.toByteArray())
    }
    
    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (x in vector) sum += x * x
        val magnitude = if (sum > 0f) sqrt(sum) else 1f
        return FloatArray(vector.size) { i -> vector[i] / magnitude }
    }
}