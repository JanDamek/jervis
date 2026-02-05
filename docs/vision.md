# Vision Processing and Smart Model

**Status:** Production Documentation (2026-02-05)
**Purpose:** Vision processing architecture and context-aware model selection

---

## Table of Contents

1. [Vision Processing Architecture](#vision-processing-architecture)
2. [Vision Fail-Fast Design](#vision-fail-fast-design)
3. [Smart Model Selector](#smart-model-selector)
4. [Vision Integration with Koog](#vision-integration-with-koog)

---

## Vision Processing Architecture

### Problem Statement

**Problem**: Apache Tika is blind - extracts text, but doesn't see **meaning** of screenshots, graphs, diagrams, and scanned PDFs.

**Solution**: Integration of **Qwen2.5-VL** (vision model) into Qualifier Agent as **LLM node**, not as Tool.

### Vision Architecture

#### ❌ What NOT TO DO (Anti-patterns)

```kotlin
// ❌ WRONG - Vision as Tool
@Tool
suspend fun analyzeAttachment(attachmentId: String): String {
    val model = selectVisionModel(...)
    return llmGateway.call(model, image) // LLM call in Tool!
}
```

**Why is it wrong:**
- Tool API is for **actions** (save to DB, create task), not for LLM calls
- We lose type-safety of Koog graph
- Cannot use Koog multimodal (different models per node)
- Complicated testing

#### ✅ What TO DO (Correct approach)

```kotlin
// ✅ CORRECT - Vision as LLM node
val nodeVision by node<QualifierPipelineState, QualifierPipelineState>("Vision Analysis") { state ->
    val model = selectVisionModel(state.attachments)
    val visionResult = llmGateway.call(model, image)
    state.withVision(visionResult)
}
```

### Vision Integration with Koog

- **Vision as LLM node**: Part of Koog strategy graph
- **Type-safe**: `QualifierPipelineState -> QualifierPipelineState`
- **Model selection**: Automatic selection of appropriate vision model
- **Context preservation**: Vision context preserved through all phases

---

## Vision Fail-Fast Design

### Design Philosophy: FAIL-FAST

Vision Augmentation follows **FAIL-FAST design** as documented in the codebase guidelines.

### What is Fail-Fast?

**Fail-Fast** means that if ANY step in the vision processing pipeline fails, the ENTIRE task fails immediately and is marked as FAILED. This is the opposite of "fail-safe" which would try to continue with partial results.

### Why Fail-Fast?

1. **Data Integrity**: Vision analysis is critical for understanding visual content. Partial results could lead to incorrect conclusions.
2. **Debugging**: Fast failures make it immediately clear where the problem occurred.
3. **Retry Logic**: FAILED tasks are retried, ensuring eventual consistency.
4. **No Silent Failures**: Every error is visible and logged.

### Implementation

#### Layer 1: Indexers (Jira/Confluence)

**Fail-Fast Points:**
- ❌ Attachment download fails → Mark Jira issue/Confluence page as FAILED
- ❌ Image dimension extraction fails → Propagate exception
- ❌ Storage fails → Propagate exception

```kotlin
// FAIL-FAST: If any attachment download/storage fails, exception propagates
// This marks the entire Jira issue as FAILED for retry
for (att in visualAttachments) {
    try {
        val image = downloadAttachment(att)
        val dimensions = extractImageDimensions(image)
        storeAttachment(att, image, dimensions)
    } catch (e: Exception) {
        log.error("Attachment processing failed", e)
        throw e  // Fail-fast: propagate exception
    }
}
```

---

## Smart Model Selector

### Overview

SmartModelSelector is a Spring service that dynamically selects optimal Ollama LLM models based on input content length. It prevents context truncation for large documents while avoiding RAM/VRAM waste on small tasks.

### Problem Statement

#### Before SmartModelSelector:
- **Hardcoded models**: All tasks use same model (e.g., `qwen3-coder:30b` with 128k context)
- **Small tasks** (1k tokens): Waste RAM/VRAM allocating 128k context
- **Large tasks** (100k tokens): Get truncated at 128k limit

#### After SmartModelSelector:
- **Dynamic selection**: Automatically chooses optimal tier based on content length
- **Efficient resource usage**: Small tasks use small context (4k-16k)
- **No truncation**: Large tasks get appropriate context (64k-256k)

### Model Naming Convention

All models on Ollama server follow this pattern:
```
qwen3-coder-tool-{SIZE}k:30b
```

### Model Selection Logic

| Content Length | Model | Context | Use Case |
|----------------|-------|---------|----------|
| 0-4,000 tokens | qwen3-coder-tool-4k:30b | 4k | Small tasks, quick queries |
| 4,001-16,000 tokens | qwen3-coder-tool-16k:30b | 16k | Medium tasks, documents |
| 16,001-64,000 tokens | qwen3-coder-tool-64k:30b | 64k | Large documents, codebases |
| 64,001+ tokens | qwen3-coder-tool-256k:30b | 256k | Very large documents |

### Implementation

```kotlin
@Service
class SmartModelSelector(
    private val ollamaClient: OllamaClient,
    private val modelConfig: ModelConfig
) {
    
    suspend fun selectModel(content: String): String {
        val tokenCount = estimateTokenCount(content)
        
        return when {
            tokenCount <= 4000 -> "qwen3-coder-tool-4k:30b"
            tokenCount <= 16000 -> "qwen3-coder-tool-16k:30b"
            tokenCount <= 64000 -> "qwen3-coder-tool-64k:30b"
            else -> "qwen3-coder-tool-256k:30b"
        }
    }
    
    private fun estimateTokenCount(content: String): Int {
        // Simple estimation: 1 token ≈ 4 characters (rough estimate)
        return content.length / 4
    }
}
```

### Benefits

1. **Cost efficiency**: GPU models only when necessary
2. **Resource optimization**: Small tasks use small context (4k-16k)
3. **No truncation**: Large tasks get appropriate context (64k-256k)
4. **Dynamic adaptation**: Automatically adjusts to content length
5. **Scalability**: Handles tasks of any size efficiently

---

## Vision Integration with Koog

### Vision as LLM Node

- **Integration**: Vision model integrated as Koog LLM node
- **Type safety**: `QualifierPipelineState -> QualifierPipelineState`
- **Model selection**: Automatic selection of appropriate vision model
- **Context preservation**: Vision context preserved through all phases

### Vision Context Management

```kotlin
data class QualifierPipelineState(
    val originalText: String,
    val attachments: List<Attachment>,
    val visionContext: VisionContext? = null,
    val processingPlan: ProcessingPlan? = null,
    val metrics: PipelineMetrics = PipelineMetrics()
) {
    fun withVision(visionResult: VisionResult): QualifierPipelineState {
        return copy(
            visionContext = VisionContext(
                extractedText = visionResult.extractedText,
                detectedObjects = visionResult.detectedObjects,
                confidenceScores = visionResult.confidenceScores
            ),
            metrics = metrics.copy(visionDuration = visionResult.duration)
        )
    }
}
```

### Vision Processing Flow

```
┌─────────────────┐
│   Attachment    │
│   Download      │
└────────┬────────┘
         ↓
┌─────────────────┐
│ Image          │
│ Preprocessing  │
└────────┬────────┘
         ↓
┌─────────────────┐
│ Vision Model   │
│ Selection      │
└────────┬────────┘
         ↓
┌─────────────────┐
│ Vision Analysis│
│ (LLM node)     │
└────────┬────────┘
         ↓
┌─────────────────┐
│ Context        │
│ Preservation   │
└─────────────────┘
```

---

## Version History

- **2026-02-04**: Consolidated from individual documentation files
- **2026-02-05**: Documentation cleanup and update