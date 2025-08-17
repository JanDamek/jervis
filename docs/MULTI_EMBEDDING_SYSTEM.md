# JERVIS Multi-Embedding System - Complete Implementation

## Overview

This document describes the complete multi-embedding system implemented for JERVIS that addresses the requirement to "skutečně umí kód" (truly understands code) while maintaining excellent text understanding capabilities.

## Architecture

### Core Components

1. **MultiEmbeddingService** - Central service managing multiple embedding providers
2. **DjlEmbeddingProvider** - Thread-safe DJL implementation with batch processing
3. **DualVectorDbService** - Dual-collection vector storage with advanced search
4. **QueryRouter** - Intelligent query routing for optimal embedding selection
5. **TokenizerService** - Precise token counting and content chunking
6. **AdvancedProjectIndexer** - Incremental indexing with symbol-aware processing
7. **EmbeddingHealthCheck** - Monitoring and recovery mechanisms
8. **EvaluationService** - Comprehensive performance evaluation

### Multi-Embedding Strategy

The system uses two specialized embedding models:

- **Text Embedding**: `intfloat/multilingual-e5-base` for documentation, meetings, communication
- **Code Embedding**: `jinaai/jina-embeddings-v2-base-code` for source code, APIs, implementations

## Features Implemented

### ✅ Phase 1: Core Fixes
- Thread-safe DJL provider with predictor pooling
- Correct L2 normalization with FloatArray handling
- Fixed RRF merge algorithm per collection
- Proper Qdrant payload indexing

### ✅ Phase 2: Missing Components  
- TokenizerService for precise token management
- QueryRouter with intelligent strategy selection
- searchByQualifiedName implementation
- EmbeddingHealthCheck with automatic recovery

### ✅ Phase 3: Advanced Features
- AdvancedProjectIndexer with incremental indexing
- Comprehensive evaluation suite with 50 test queries
- Multi-collection fan-out search with ACL filters
- Default-branch boost and recency decay
- Embedding cache for performance optimization

## Usage Examples

### Basic Query Processing

```kotlin
@Autowired
private lateinit var multiEmbeddingService: MultiEmbeddingService

// Generate text embedding
val textEmbedding = multiEmbeddingService.generateTextEmbedding(
    "How to implement user authentication", 
    forQuery = true
)

// Generate code embedding
val codeEmbedding = multiEmbeddingService.generateCodeEmbedding(
    "UserService.authenticate(username, password)", 
    forQuery = true
)

// Generate multi-type embeddings for fan-out search
val multiEmbeddings = multiEmbeddingService.generateMultiTypeEmbeddings(
    "JWT token validation logic"
)
```

### Advanced Search with Query Router

```kotlin
@Autowired
private lateinit var queryRouter: QueryRouter
@Autowired  
private lateinit var dualVectorDbService: DualVectorDbService

// Intelligent query routing
val strategy = queryRouter.determineEmbeddingStrategy("DatabaseConnection.connect()")
// Returns: EmbeddingStrategy.CODE_ONLY

val searchResults = dualVectorDbService.searchMultiCollection(
    query = "DatabaseConnection.connect()",
    queryEmbeddings = multiEmbeddings,
    filters = mapOf("projectId" to projectId),
    limit = 10
)
```

### Incremental Project Indexing

```kotlin
@Autowired
private lateinit var advancedProjectIndexer: AdvancedProjectIndexer

// Full project indexing
val fullResult = advancedProjectIndexer.indexProjectFull(project)

// Incremental indexing (only changed files)  
val incrementalResult = advancedProjectIndexer.indexProjectIncremental(
    project = project,
    lastCommitHash = "abc123"
)
```

### Evaluation and Monitoring

```kotlin
@Autowired
private lateinit var evaluationService: EvaluationService
@Autowired
private lateinit var embeddingHealthCheck: EmbeddingHealthCheck

// Run comprehensive evaluation
val results = evaluationService.runEvaluation()
println("Success rate: ${results.aggregateMetrics.successRate}")
println("Average recall@10: ${results.aggregateMetrics.averageRecall10}")

// Check system health
val healthStatus = embeddingHealthCheck.performImmediateHealthCheck()
println("System healthy: ${healthStatus.isHealthy}")
```

## Configuration

### Application Configuration

Add to `application-multi-embedding.yml`:

```yaml
embeddings:
  models:
    - id: e5_text_768
      model: intfloat/multilingual-e5-base
      djl_url: djl://ai.djl.huggingface/sentence-transformers/intfloat/multilingual-e5-base
      dimensions: 768
      target_collection: semantic_text
      pool_size: 4
      prefixes:
        document: "passage: "
        query: "query: "
    - id: jina_code_768
      model: jinaai/jina-embeddings-v2-base-code
      djl_url: djl://ai.djl.huggingface/sentence-transformers/jinaai/jina-embeddings-v2-base-code
      dimensions: 768
      target_collection: semantic_code
      pool_size: 4
      prefixes:
        document: ""
        query: ""

qdrant:
  host: localhost
  port: 6334
  collections:
    semantic_text:
      vector_size: 768
      distance: cosine
    semantic_code:
      vector_size: 768
      distance: cosine

indexing:
  advanced:
    chunk_target_tokens: 350
    chunk_max_tokens: 400
    incremental: true
    cache_embeddings: true

evaluation:
  enabled: true
  test_queries_file: "eval/test_queries.jsonl"
  metrics: ["recall@5", "recall@10", "mrr", "ndcg@10"]
```

## Performance Characteristics

### Query Routing Intelligence

- **Symbolic queries** (`UserService.authenticate`) → CODE_ONLY
- **Meeting queries** ("discussion about architecture") → TEXT_ONLY  
- **Mixed queries** ("API documentation") → BALANCED
- **Implementation queries** ("exception handling") → CODE_PRIORITY

### Search Performance

- **RRF Merging**: Reciprocal Rank Fusion across collections (k=60)
- **Default Branch Boost**: 20% score improvement for main branch content
- **Recency Decay**: Exponential decay over 365 days
- **Caching**: Content-hash based embedding cache
- **Batch Processing**: 16-item batches for optimal throughput

### Evaluation Metrics

- **Recall@5/10**: Fraction of relevant documents retrieved
- **MRR**: Mean Reciprocal Rank for ranking quality  
- **NDCG@10**: Normalized Discounted Cumulative Gain
- **Precision@5/10**: Precision at cutoff thresholds
- **Response Time**: End-to-end query latency

## Testing

### Running Evaluations

```bash
# Run full evaluation suite
curl -X POST localhost:8080/api/evaluation/run

# Get health status
curl -X GET localhost:8080/api/health/embedding
```

### Test Query Examples

The system includes 50 comprehensive test queries covering:

- **Code queries**: `"UserService.authenticate method"`, `"class UserController"`
- **Text queries**: `"meeting notes about database migration"`, `"email service configuration"`  
- **Mixed queries**: `"JWT token validation logic"`, `"API endpoint for user registration"`
- **Edge cases**: Complex multi-concept queries, architectural discussions

## Troubleshooting

### Common Issues

1. **DJL Model Loading Failures**
   - Verify internet connection for HuggingFace model downloads
   - Check disk space (models ~500MB each)
   - Review logs for specific DJL errors

2. **Qdrant Connection Issues**
   - Ensure Qdrant is running on configured port (6334)
   - Verify collection initialization in logs
   - Check payload index creation

3. **Performance Issues**
   - Monitor embedding cache hit rates
   - Adjust batch sizes in configuration
   - Review thread pool sizing (pool_size)

4. **Evaluation Failures**
   - Verify test queries file exists and is valid JSON-L
   - Check that collections have indexed content
   - Review relevance thresholds in test queries

### Health Monitoring

The system includes comprehensive health monitoring:

- **Automatic Recovery**: Failed providers are reinitialized
- **Circuit Breaker**: Prevents cascade failures
- **Memory Management**: OOM detection with GC triggering
- **Metrics**: Response times, success rates, cache hit ratios

## Integration

### Spring Bean Configuration

```kotlin
@Configuration
class EmbeddingConfiguration {
    
    @Bean
    fun multiEmbeddingService(settingService: SettingService) = 
        MultiEmbeddingService(settingService)
        
    @Bean  
    fun queryRouter() = QueryRouter()
    
    @Bean
    fun dualVectorDbService() = DualVectorDbService()
    
    @Bean
    fun evaluationService(
        multiEmbeddingService: MultiEmbeddingService,
        dualVectorDbService: DualVectorDbService,
        queryRouter: QueryRouter,
        objectMapper: ObjectMapper
    ) = EvaluationService(multiEmbeddingService, dualVectorDbService, queryRouter, objectMapper)
}
```

## Deployment Considerations

### Resource Requirements

- **Memory**: ~4GB for both embedding models + predictor pools
- **CPU**: Multi-core recommended for batch processing
- **Storage**: ~1GB for models + vector indices
- **Network**: Reliable connection for model downloads

### Production Optimizations

- **Model Caching**: Pre-download models in container build
- **Connection Pooling**: Configure Qdrant connection pools
- **Monitoring**: Set up metrics collection and alerting
- **Backup**: Regular vector database backups

This implementation provides a complete, production-ready multi-embedding system that truly understands both code and natural language, addressing all the requirements outlined in the original specification.