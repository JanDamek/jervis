# Vision Augmentation Architecture

## 📋 Přehled

**Problém**: Apache Tika je slepá - extrahuje text, ale nevidí **význam** screenshotů, grafů, diagramů a scannovaných PDF.

**Řešení**: Integrace **Qwen2.5-VL** (vision model) do Qualifier Agent jako **LLM node**, ne jako Tool.

---

## 🎯 Klíčové principy

### ❌ Co NEDĚLAT (Anti-patterns)

```kotlin
// ❌ ŠPATNĚ - Vision jako Tool
@Tool
fun analyzeAttachment(attachmentId: String): String {
    val model = selectVisionModel(...)
    return llmGateway.call(model, image) // LLM call v Toolu!
}
```

**Proč je to špatně:**
- Tool API je pro **akce** (uložit do DB, vytvořit task), ne pro LLM cally
- Ztrácíme type-safety Koog grafu
- Nelze využít Koog multimodal (různé modely per node)
- Komplikované testování

### ✅ Co DĚLAT (Správný přístup)

```kotlin
// ✅ SPRÁVNĚ - Vision jako LLM Node v grafu
val nodeVisionAugmentation by nodeLLMRequest<ChunkWithContext, AugmentedChunk>(
    name = "Vision Augmentation",
    modelOverride = visionModel,  // Koog multimodal
    promptBuilder = { context ->
        Prompt.build("vision") {
            system("Analyze images...")
            user {
                text(context.chunkText)
                image(context.attachments[0].binaryData) // Koog image DSL
            }
        }
    }
)
```

**Proč je to správně:**
- Vision je **LLM call**, proto je to **node v grafu**
- Koog multimodal: `modelOverride` per node
- Type-safe data flow: `ChunkWithContext → AugmentedChunk`
- Conditional routing: `.onCondition { hasAttachments }`
- Snadné testování: node = pure function

---

## 🏗️ Architektura (3 Layers)

```
┌─────────────────────────────────────────────────────────────────┐
│ LAYER 1: CONTINUOUS INDEXER                                     │
│  - Extract attachments from Jira/Confluence/Email               │
│  - Download binaries (images, PDFs)                             │
│  - Store in DirectoryStructureService                           │
│  - Attach metadata to PendingTask                               │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ LAYER 2: QUALIFIER AGENT (CPU - Map/Reduce)                     │
│  Phase 1: SPLIT (semantic chunking)                             │
│  Phase 2: MAP (per-chunk processing)                            │
│    ├─ PrepareChunk: Text + attachments                          │
│    ├─ VisionAugmentation: Qwen2.5-VL analysis (conditional)     │
│    └─ ExtractKnowledge: Store to RAG + Graph                    │
│  Phase 3: REDUCE (synthesis)                                    │
│  Phase 4: TASK CREATION                                         │
│  Phase 5: ROUTING                                               │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ LAYER 3: WORKFLOW AGENT (GPU - Deep Processing)                 │
│  - If LIFT_UP: Can re-analyze attachments with more powerful    │
│    vision model (e.g., qwen3-vl-tool-32k:latest on GPU)         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📐 Data Model

### PendingTaskDocument Extension

```kotlin
@Document(collection = "pending_tasks")
data class PendingTaskDocument(
    // ... existing fields

    /** Attachments for vision analysis */
    val attachments: List<AttachmentMetadata> = emptyList(),
)

@Serializable
data class AttachmentMetadata(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String, // Relative to DirectoryStructureService
    val type: AttachmentType,
    val widthPixels: Int?, // For image token estimation
    val heightPixels: Int?, // For image token estimation
)

enum class AttachmentType {
    IMAGE,          // PNG, JPG, JPEG - high priority for vision
    PDF_SCANNED,    // PDF where Tika returned empty (needs OCR)
    PDF_STRUCTURED, // PDF where Tika got text (may still have charts)
    DOCUMENT,       // DOCX, XLSX, PPTX
    UNKNOWN,
}
```

### Qualifier Agent Data Flow

```kotlin
// Phase 2: MAP - Chunk processing with vision

data class ChunkWithContext(
    val chunkText: String,
    val chunkIndex: Int,
    val referencedAttachments: List<AttachmentData>, // Filtered for this chunk
)

data class AttachmentData(
    val id: String,
    val filename: String,
    val mimeType: String,
    val type: AttachmentType,
    val binaryData: ByteArray, // Loaded in memory
    val widthPixels: Int,
    val heightPixels: Int,
)

data class AugmentedChunk(
    val originalText: String,
    val visionDescriptions: List<VisionDescription>,
    val chunkIndex: Int,
) {
    fun toCombinedText(): String = buildString {
        append(originalText)
        if (visionDescriptions.isNotEmpty()) {
            append("\n\n## Visual Content Analysis\n\n")
            visionDescriptions.forEach { vision ->
                append("### ${vision.filename}\n")
                append(vision.description)
                append("\n\n")
            }
        }
    }
}

data class VisionDescription(
    val attachmentId: String,
    val filename: String,
    val model: String, // e.g., "qwen3-vl-tool-16k:latest"
    val description: String,
)
```

---

## 🧩 Smart Model Selection (Dynamic Context)

### Vision Model Selection with Token Estimation

```kotlin
@Service
class SmartModelSelector(
    private val tokenCountingService: TokenCountingService,
) {
    companion object {
        private val AVAILABLE_TIERS = listOf(4, 8, 16, 32, 40, 48, 64, 80, 96, 112, 128, 192, 256)
        private const val IMAGE_TOKEN_COMPRESSION_RATIO = 400
    }

    data class ImageMetadata(
        val widthPixels: Int,
        val heightPixels: Int,
    ) {
        fun estimateTokens(): Int {
            val pixels = widthPixels * heightPixels
            return (pixels / IMAGE_TOKEN_COMPRESSION_RATIO).coerceAtLeast(100)
        }
    }

    /**
     * Select vision model with dynamic context based on:
     * - Text prompt tokens
     * - Image resolution (pixels → tokens)
     * - Output reserve
     */
    fun selectVisionModel(
        baseModelName: String,          // "qwen3-vl:latest"
        textPrompt: String,
        images: List<ImageMetadata>,
        outputReserve: Int = 2000,
    ): LLModel {
        val textTokens = tokenCountingService.countTokens(textPrompt)
        val imageTokens = images.sumOf { it.estimateTokens() }
        val totalTokensNeeded = textTokens + imageTokens + outputReserve

        val selectedTierK = AVAILABLE_TIERS.firstOrNull { (it * 1024) >= totalTokensNeeded }
            ?: AVAILABLE_TIERS.last()

        val modelId = insertTierIntoModelName(baseModelName, selectedTierK)
        // "qwen3-vl:latest" + 16k → "qwen3-vl-tool-16k:latest"

        return LLModel(
            provider = LLMProvider.Ollama,
            id = modelId,
            capabilities = listOf(LLMCapability.Vision),
            contextLength = (selectedTierK * 1024).toLong(),
        )
    }
}
```

### Token Estimation Examples

| Image Resolution | Estimated Tokens | Selected Tier |
|------------------|------------------|---------------|
| 512×512 (screenshot) | ~650 tokens | 4k |
| 1024×1024 (chart) | ~2600 tokens | 4k-8k |
| 2048×2048 (document scan) | ~10000 tokens | 16k |
| 4096×4096 (high-res PDF page) | ~40000 tokens | 48k |

**Formula**: `tokens ≈ (width × height) / 400`

**Ollama Modelfiles** (už existují - vygenerováno build scriptem):
```modelfile
FROM qwen3-vl:latest
PARAMETER num_ctx 4096
PARAMETER temperature 0.1

FROM qwen3-vl:latest
PARAMETER num_ctx 8192
PARAMETER temperature 0.1

FROM qwen3-vl:latest
PARAMETER num_ctx 16384
PARAMETER temperature 0.1

# Všechny tiers: 4k, 8k, 16k, 32k, 40k, 48k, 64k, 80k, 96k, 112k, 128k, 192k, 256k
```

---

## 🔄 Qualifier Agent Strategy (Phase 2 - MAP)

### Vision Node Integration

```kotlin
val subgraphProcessing by subgraph<ProcessingState, ProcessingState>(
    name = "📋 MAP Phase"
) {

    // Node 1: Prepare chunk + filter attachments
    val nodePrepareChunk by node<ProcessingState, ChunkWithContext>(
        name = "Prepare Chunk"
    ) { state ->
        val chunkText = state.nextChunk() ?: error("No chunk")

        // Filter attachments referenced in this chunk
        val referencedAttachments = state.attachments.filter { att ->
            chunkText.contains(att.filename, ignoreCase = true) ||
            chunkText.contains("[IMAGE]") ||
            chunkText.contains("screenshot", ignoreCase = true)
        }.map { metadata ->
            // Load binary from DirectoryStructureService
            val binary = directoryStructureService.readAttachment(metadata.storagePath)
            AttachmentData(
                id = metadata.id,
                filename = metadata.filename,
                mimeType = metadata.mimeType,
                type = metadata.type,
                binaryData = binary,
                widthPixels = metadata.widthPixels ?: 1024,
                heightPixels = metadata.heightPixels ?: 1024,
            )
        }

        ChunkWithContext(
            chunkText = chunkText,
            chunkIndex = state.currentIndex,
            referencedAttachments = referencedAttachments,
        )
    }

    // Node 2: Vision augmentation (MULTIMODAL - Qwen2.5-VL)
    val nodeVisionAugmentation by nodeLLMRequest<ChunkWithContext, AugmentedChunk>(
        name = "Vision Augmentation",
        modelSelector = { context ->
            // Dynamic model selection based on image resolution
            val images = context.referencedAttachments.map { att ->
                SmartModelSelector.ImageMetadata(
                    widthPixels = att.widthPixels,
                    heightPixels = att.heightPixels,
                )
            }

            val visionPrompt = buildVisionPrompt(context.chunkText)

            smartModelSelector.selectVisionModel(
                baseModelName = "qwen3-vl:latest",
                textPrompt = visionPrompt,
                images = images,
                outputReserve = 2000,
            )
        },
        promptBuilder = { context ->
            Prompt.build("vision-analysis") {
                system("""
                    You are a Vision Analysis Expert for knowledge extraction.

                    Analyze attached images and provide factual descriptions.

                    **Focus areas:**
                    - ERROR SCREENSHOTS: Extract exact error text, UI context, stack traces
                    - GRAPHS/CHARTS: Describe trends, metrics, conclusions
                    - FORMS/DOCUMENTS: Extract structured data (key-value pairs)
                    - DIAGRAMS: Describe components and relationships

                    **Output format:** Markdown with clear headings.
                    **Style:** Factual, no speculation.
                """.trimIndent())

                user {
                    text("""
                        Context from document:
                        ${context.chunkText}

                        Analyze the following ${context.referencedAttachments.size} attachment(s):
                    """.trimIndent())

                    // Koog multimodal: Add images
                    context.referencedAttachments.forEach { att ->
                        text("\n\n### ${att.filename}")
                        image(att.binaryData) // Koog DSL
                    }
                }
            }
        },
        responseParser = { response, context ->
            val visionDescriptions = context.referencedAttachments.map { att ->
                VisionDescription(
                    attachmentId = att.id,
                    filename = att.filename,
                    model = response.model ?: "qwen3-vl",
                    description = response.content,
                )
            }

            AugmentedChunk(
                originalText = context.chunkText,
                visionDescriptions = visionDescriptions,
                chunkIndex = context.chunkIndex,
            )
        }
    )

    // Node 3: Extract knowledge (MULTIMODAL - Back to text model)
    val nodeExtractKnowledge by nodeLLMRequest<AugmentedChunk, String>(
        name = "Extract Knowledge",
        // Uses default model from AIAgentConfig (qwen3-coder-tool:30b)
        promptBuilder = { augmentedChunk ->
            Prompt.build("analyze-chunk") {
                system(promptRepository.getSystemPrompt("ANALYZE_CHUNK"))
                user(augmentedChunk.toCombinedText()) // Text + vision
            }
        }
    )

    // Node 4: Execute storeKnowledge() tool
    val nodeExecuteTools by nodeExecuteTool(name = "Store Knowledge")

    // Node 5: Record result
    val nodeRecordResult by node<String, ProcessingState>("Record Result") { result ->
        val chunkResult = parseChunkResult(result)
        currentState.copy(
            processedResults = currentState.processedResults + chunkResult,
            currentIndex = currentState.currentIndex + 1,
        )
    }

    // EDGES
    edge(nodeStart forwardTo nodePrepareChunk)

    // Conditional: Vision only if attachments present
    edge(nodePrepareChunk forwardTo nodeVisionAugmentation)
        .onCondition { ctx -> ctx.referencedAttachments.isNotEmpty() }

    // Skip vision if no attachments
    edge(nodePrepareChunk forwardTo nodeExtractKnowledge)
        .onCondition { ctx -> ctx.referencedAttachments.isEmpty() }
        .transformInput { ctx ->
            AugmentedChunk(ctx.chunkText, emptyList(), ctx.chunkIndex)
        }

    edge(nodeVisionAugmentation forwardTo nodeExtractKnowledge)
    edge(nodeExtractKnowledge forwardTo nodeExecuteTools)
    edge(nodeExecuteTools forwardTo nodeRecordResult)

    // Loop back if more chunks
    edge(nodeRecordResult forwardTo nodePrepareChunk)
        .onCondition { state -> state.hasMoreChunks() }

    edge(nodeRecordResult forwardTo nodeFinish)
        .onCondition { state -> !state.hasMoreChunks() }
}
```

---

## 📝 Vision Prompts (prompts.yaml)

```yaml
prompts:
  vision:
    error_screenshot:
      system: |
        You are a Vision Analysis Expert specializing in software error screenshots.

        **Task:** Extract all visible information from error screenshots.

        **Output:**
        - Exact error text (no paraphrasing)
        - Error codes and stack traces (if visible)
        - UI context (which button/form caused it)
        - User actions leading to error

        **Style:** Factual, precise, no speculation.

    graph_analysis:
      system: |
        You are a Data Visualization Analyst.

        **Task:** Extract insights from charts and graphs.

        **Output:**
        - Data trends (rising, falling, stable)
        - Key metrics and values
        - Time periods
        - Main conclusion (1-2 sentences)

        **Style:** Focus on data, ignore visual styling.

    form_extraction:
      system: |
        You are a Document OCR Specialist.

        **Task:** Extract structured data from forms and documents.

        **Output:**
        - Key-value pairs (field names and values)
        - Tables (as markdown)
        - Signatures and dates

        **Style:** Structured, machine-readable.
```

---

## 🚀 Implementační kroky

### Fáze 1: Data Model (DONE)
- ✅ `AttachmentMetadata` data class
- ✅ `ChunkWithContext`, `AugmentedChunk`, `VisionDescription`
- ✅ `SmartModelSelector.selectVisionModel()`
- ✅ `SmartModelSelector.ImageMetadata` s token estimation

### Fáze 2: Indexer (TODO)
- [ ] Upravit `JiraContinuousIndexer` - stáhnout attachments
- [ ] Upravit `ConfluenceContinuousIndexer` - stáhnout obrázky z pages
- [ ] Upravit `EmailContinuousIndexer` - stáhnout email attachments
- [ ] `DirectoryStructureService.storeAttachment()` - uložit binární data
- [ ] `PendingTaskService.createTask()` - přidat `attachments` parameter

### Fáze 3: Qualifier Agent (TODO)
- [ ] Přidat `attachments` do `ProcessingState`
- [ ] Node `nodePrepareChunk` - filtrovat attachments per chunk
- [ ] Node `nodeVisionAugmentation` - Koog multimodal LLM call
- [ ] Conditional edges - skip vision pokud nejsou attachments
- [ ] Prompt templates v `prompts.yaml`

### Fáze 4: Testing (TODO)
- [ ] Unit test `SmartModelSelector.selectVisionModel()`
- [ ] Integration test vision node s mock Ollama
- [ ] E2E test Jira issue → vision analysis → knowledge graph

### Fáze 5: Documentation (TODO)
- [ ] Update `qualifier-agent-strategy.md`
- [ ] Update `koog-libraries.md` s multimodal příklady
- [ ] Create Ollama Modelfile examples pro vision tiers

---

## 🎯 Benefits

1. **Systémově čistý design** - Vision je LLM node, ne Tool hack
2. **Koog multimodal** - Každý node může mít jiný model (text vs vision)
3. **Dynamic context** - Context window se přizpůsobuje rozlišení obrázků
4. **Type-safe** - Compile-time check data flow
5. **Conditional execution** - Vision běží jen když je potřeba
6. **Backwards compatible** - Existující flow funguje bez attachments
7. **Testovatelný** - Nodes jsou pure functions

---

## 📊 Vision Token Consumption Examples

### Scénář 1: Jira Bug Report
```
Chunk text: "Application crashes when clicking Save. See screenshot."
Attachment: screenshot.png (1920x1080)

Token calculation:
- Text: 200 tokens
- Image: (1920 × 1080) / 400 = 5184 tokens
- Output reserve: 2000 tokens
- TOTAL: 7384 tokens → Selected tier: 8k

Model: qwen3-vl-tool-8k:latest
```

### Scénář 2: Confluence Chart
```
Chunk text: "Q3 sales performance analysis. Chart below shows..."
Attachment: sales_chart.png (800x600)

Token calculation:
- Text: 150 tokens
- Image: (800 × 600) / 400 = 1200 tokens
- Output reserve: 2000 tokens
- TOTAL: 3350 tokens → Selected tier: 4k

Model: qwen3-vl-tool-4k:latest
```

### Scénář 3: Email with PDF
```
Chunk text: "Contract for review. Terms on page 3."
Attachment: contract.pdf (scanned, 2480x3508 per page)

Token calculation:
- Text: 100 tokens
- Image (1 page): (2480 × 3508) / 400 = 21,764 tokens
- Output reserve: 2000 tokens
- TOTAL: 23,864 tokens → Selected tier: 32k

Model: qwen3-vl-tool-32k:latest
```

---

## 🔒 Safety & Limits

```yaml
jervis:
  vision:
    enabled: true
    max-attachments-per-chunk: 3  # Prevent token explosion
    max-resolution: 4096x4096      # Downscale larger images
    supported-formats:
      - image/png
      - image/jpeg
      - image/jpg
      - image/webp
      - application/pdf
    confidence-threshold: 0.7       # Trigger vision if Tika confidence < 0.7
```

---

## 📚 References

- Koog Framework: `docs/koog-libraries.md`
- Qualifier Agent Strategy: `docs/qualifier-agent-strategy.md`
- Smart Model Selector: `backend/server/src/main/kotlin/com/jervis/koog/SmartModelSelector.kt`
- Qwen2-VL Model Card: https://huggingface.co/Qwen/Qwen2-VL-7B-Instruct
