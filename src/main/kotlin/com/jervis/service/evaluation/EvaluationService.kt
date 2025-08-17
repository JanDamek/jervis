package com.jervis.service.evaluation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jervis.service.indexer.MultiEmbeddingService
import com.jervis.service.indexer.QueryRouter
import com.jervis.service.indexer.EmbeddingStrategy
import com.jervis.service.vectordb.VectorStorageService
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.io.File
import kotlin.math.log2
import kotlin.math.pow

@Service
class EvaluationService(
    private val multiEmbeddingService: MultiEmbeddingService,
    private val vectorStorageService: VectorStorageService,
    private val queryRouter: QueryRouter,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}
    
    /**
     * Run comprehensive evaluation of the multi-embedding system
     */
    suspend fun runEvaluation(
        testQueriesFile: String = "eval/test_queries.jsonl",
        outputDir: String = "eval/results"
    ): EvaluationResults = coroutineScope {
        logger.info { "Starting multi-embedding system evaluation..." }
        
        try {
            // Load test queries
            val testQueries = loadTestQueries(testQueriesFile)
            logger.info { "Loaded ${testQueries.size} test queries" }
            
            // Initialize results tracking
            val queryResults = mutableListOf<QueryEvaluationResult>()
            
            // Evaluate each query
            testQueries.forEach { testQuery ->
                try {
                    val result = evaluateQuery(testQuery)
                    queryResults.add(result)
                    logger.debug { "Evaluated query ${testQuery.id}: ${testQuery.query}" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to evaluate query ${testQuery.id}: ${e.message}" }
                    queryResults.add(QueryEvaluationResult(
                        queryId = testQuery.id,
                        query = testQuery.query,
                        queryType = testQuery.type,
                        success = false,
                        errorMessage = e.message
                    ))
                }
            }
            
            // Calculate aggregate metrics
            val aggregateMetrics = calculateAggregateMetrics(queryResults)
            
            // Generate detailed results
            val evaluationResults = EvaluationResults(
                totalQueries = testQueries.size,
                successfulQueries = queryResults.count { it.success },
                failedQueries = queryResults.count { !it.success },
                aggregateMetrics = aggregateMetrics,
                queryResults = queryResults,
                timestamp = System.currentTimeMillis()
            )
            
            // Save results
            saveResults(evaluationResults, outputDir)
            
            logger.info { "Evaluation completed successfully. Success rate: ${aggregateMetrics.successRate}" }
            return@coroutineScope evaluationResults
            
        } catch (e: Exception) {
            logger.error(e) { "Evaluation failed: ${e.message}" }
            throw e
        }
    }
    
    /**
     * Evaluate a single test query
     */
    private suspend fun evaluateQuery(testQuery: TestQuery): QueryEvaluationResult {
        val startTime = System.currentTimeMillis()
        
        try {
            // Determine embedding strategy
            val strategy = queryRouter.determineEmbeddingStrategy(testQuery.query)
            
            // Generate embeddings based on strategy
            val queryEmbeddings = when (strategy) {
                EmbeddingStrategy.TEXT_ONLY -> mapOf(
                    "semantic_text" to multiEmbeddingService.generateTextEmbedding(testQuery.query, true)
                )
                EmbeddingStrategy.CODE_ONLY -> mapOf(
                    "semantic_code" to multiEmbeddingService.generateCodeEmbedding(testQuery.query, true)
                )
                else -> multiEmbeddingService.generateMultiTypeEmbeddings(testQuery.query, true)
            }
            
            // Search across collections
            val searchResults = vectorStorageService.searchMultiCollection(
                query = testQuery.query,
                queryEmbeddings = queryEmbeddings,
                filters = emptyMap<String, Any>(),
                limit = 10
            )
            
            val responseTime = System.currentTimeMillis() - startTime
            
            // Calculate metrics
            val metrics = calculateQueryMetrics(testQuery, searchResults)
            
            return QueryEvaluationResult(
                queryId = testQuery.id,
                query = testQuery.query,
                queryType = testQuery.type,
                strategy = strategy.name,
                success = true,
                responseTimeMs = responseTime,
                resultCount = searchResults.size,
                metrics = metrics,
                searchResults = searchResults.map { result ->
                    SearchResultSummary(
                        id = result.id,
                        score = result.score,
                        collection = result.collection
                    )
                }
            )
            
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            return QueryEvaluationResult(
                queryId = testQuery.id,
                query = testQuery.query,
                queryType = testQuery.type,
                success = false,
                responseTimeMs = responseTime,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Calculate metrics for a single query
     */
    private fun calculateQueryMetrics(
        testQuery: TestQuery,
        searchResults: List<com.jervis.service.vectordb.ScoredDocument>
    ): QueryMetrics {
        // For this evaluation, we simulate relevance based on collection matching
        // In a real scenario, you'd have ground truth relevance labels
        val relevantResults = searchResults.filter { result ->
            testQuery.expectedCollections.contains(result.collection) &&
            result.score >= testQuery.relevanceThreshold
        }
        
        val recall5 = calculateRecall(relevantResults, searchResults.take(5))
        val recall10 = calculateRecall(relevantResults, searchResults.take(10))
        val mrr = calculateMRR(relevantResults, searchResults)
        val ndcg10 = calculateNDCG(relevantResults, searchResults.take(10))
        
        return QueryMetrics(
            recall5 = recall5,
            recall10 = recall10,
            mrr = mrr,
            ndcg10 = ndcg10,
            precision5 = if (searchResults.size >= 5) relevantResults.take(5).size / 5.0 else 0.0,
            precision10 = if (searchResults.size >= 10) relevantResults.take(10).size / 10.0 else 0.0
        )
    }
    
    /**
     * Calculate recall metric
     */
    private fun calculateRecall(
        relevantResults: List<com.jervis.service.vectordb.ScoredDocument>,
        retrievedResults: List<com.jervis.service.vectordb.ScoredDocument>
    ): Double {
        if (relevantResults.isEmpty()) return 0.0
        
        val retrievedRelevant = retrievedResults.filter { retrieved ->
            relevantResults.any { relevant -> relevant.id == retrieved.id }
        }
        
        return retrievedRelevant.size.toDouble() / relevantResults.size
    }
    
    /**
     * Calculate Mean Reciprocal Rank (MRR)
     */
    private fun calculateMRR(
        relevantResults: List<com.jervis.service.vectordb.ScoredDocument>,
        searchResults: List<com.jervis.service.vectordb.ScoredDocument>
    ): Double {
        if (relevantResults.isEmpty()) return 0.0
        
        searchResults.forEachIndexed { index, result ->
            if (relevantResults.any { it.id == result.id }) {
                return 1.0 / (index + 1)
            }
        }
        
        return 0.0
    }
    
    /**
     * Calculate Normalized Discounted Cumulative Gain (NDCG)
     */
    private fun calculateNDCG(
        relevantResults: List<com.jervis.service.vectordb.ScoredDocument>,
        searchResults: List<com.jervis.service.vectordb.ScoredDocument>
    ): Double {
        if (relevantResults.isEmpty()) return 0.0
        
        // Calculate DCG
        var dcg = 0.0
        searchResults.forEachIndexed { index, result ->
            if (relevantResults.any { it.id == result.id }) {
                val relevance = 1.0 // Binary relevance
                dcg += relevance / log2((index + 2).toDouble())
            }
        }
        
        // Calculate IDCG (perfect ranking)
        var idcg = 0.0
        val idealRanking = relevantResults.take(searchResults.size)
        idealRanking.forEachIndexed { index, _ ->
            val relevance = 1.0
            idcg += relevance / log2((index + 2).toDouble())
        }
        
        return if (idcg > 0) dcg / idcg else 0.0
    }
    
    /**
     * Calculate aggregate metrics across all queries
     */
    private fun calculateAggregateMetrics(queryResults: List<QueryEvaluationResult>): AggregateMetrics {
        val successfulResults = queryResults.filter { it.success && it.metrics != null }
        
        if (successfulResults.isEmpty()) {
            return AggregateMetrics(
                successRate = 0.0,
                averageResponseTime = 0.0,
                averageRecall5 = 0.0,
                averageRecall10 = 0.0,
                averageMRR = 0.0,
                averageNDCG10 = 0.0
            )
        }
        
        return AggregateMetrics(
            successRate = successfulResults.size.toDouble() / queryResults.size,
            averageResponseTime = successfulResults.mapNotNull { it.responseTimeMs }.average(),
            averageRecall5 = successfulResults.mapNotNull { it.metrics?.recall5 }.average(),
            averageRecall10 = successfulResults.mapNotNull { it.metrics?.recall10 }.average(),
            averageMRR = successfulResults.mapNotNull { it.metrics?.mrr }.average(),
            averageNDCG10 = successfulResults.mapNotNull { it.metrics?.ndcg10 }.average(),
            averagePrecision5 = successfulResults.mapNotNull { it.metrics?.precision5 }.average(),
            averagePrecision10 = successfulResults.mapNotNull { it.metrics?.precision10 }.average(),
            queryTypeBreakdown = calculateQueryTypeBreakdown(successfulResults)
        )
    }
    
    /**
     * Calculate performance breakdown by query type
     */
    private fun calculateQueryTypeBreakdown(results: List<QueryEvaluationResult>): Map<String, TypeMetrics> {
        return results.groupBy { it.queryType }.mapValues { (_, typeResults) ->
            val metrics = typeResults.mapNotNull { it.metrics }
            TypeMetrics(
                count = typeResults.size,
                averageRecall5 = metrics.map { it.recall5 }.average(),
                averageRecall10 = metrics.map { it.recall10 }.average(),
                averageMRR = metrics.map { it.mrr }.average(),
                averageNDCG10 = metrics.map { it.ndcg10 }.average()
            )
        }
    }
    
    /**
     * Load test queries from JSONL file
     */
    private fun loadTestQueries(filePath: String): List<TestQuery> {
        return try {
            val resource = ClassPathResource(filePath)
            val lines = resource.inputStream.bufferedReader().readLines()
            
            lines.map { line ->
                objectMapper.readValue<TestQuery>(line)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load test queries from $filePath: ${e.message}" }
            emptyList()
        }
    }
    
    /**
     * Save evaluation results
     */
    private fun saveResults(results: EvaluationResults, outputDir: String) {
        try {
            val outputFile = File(outputDir, "evaluation_results_${System.currentTimeMillis()}.json")
            outputFile.parentFile.mkdirs()
            
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, results)
            logger.info { "Evaluation results saved to ${outputFile.absolutePath}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save evaluation results: ${e.message}" }
        }
    }
}

// Data classes for evaluation
data class TestQuery(
    val id: Int,
    val query: String,
    val type: String,
    val expectedCollections: List<String>,
    val relevanceThreshold: Double
)

data class QueryEvaluationResult(
    val queryId: Int,
    val query: String,
    val queryType: String,
    val strategy: String? = null,
    val success: Boolean,
    val responseTimeMs: Long? = null,
    val resultCount: Int? = null,
    val metrics: QueryMetrics? = null,
    val searchResults: List<SearchResultSummary>? = null,
    val errorMessage: String? = null
)

data class QueryMetrics(
    val recall5: Double,
    val recall10: Double,
    val mrr: Double,
    val ndcg10: Double,
    val precision5: Double,
    val precision10: Double
)

data class SearchResultSummary(
    val id: String,
    val score: Float,
    val collection: String
)

data class EvaluationResults(
    val totalQueries: Int,
    val successfulQueries: Int,
    val failedQueries: Int,
    val aggregateMetrics: AggregateMetrics,
    val queryResults: List<QueryEvaluationResult>,
    val timestamp: Long
)

data class AggregateMetrics(
    val successRate: Double,
    val averageResponseTime: Double,
    val averageRecall5: Double,
    val averageRecall10: Double,
    val averageMRR: Double,
    val averageNDCG10: Double,
    val averagePrecision5: Double = 0.0,
    val averagePrecision10: Double = 0.0,
    val queryTypeBreakdown: Map<String, TypeMetrics> = emptyMap()
)

data class TypeMetrics(
    val count: Int,
    val averageRecall5: Double,
    val averageRecall10: Double,
    val averageMRR: Double,
    val averageNDCG10: Double
)