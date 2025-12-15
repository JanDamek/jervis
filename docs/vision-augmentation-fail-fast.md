# Vision Augmentation - Fail-Fast Design

## Design Philosophy: FAIL-FAST

Vision Augmentation follows **FAIL-FAST design** as documented in the codebase guidelines.

### What is Fail-Fast?

**Fail-Fast** means that if ANY step in the vision processing pipeline fails, the ENTIRE task fails immediately and is marked as FAILED. This is the opposite of "fail-safe" which would try to continue with partial results.

### Why Fail-Fast?

1. **Data Integrity**: Vision analysis is critical for understanding visual content. Partial results could lead to incorrect conclusions.
2. **Debugging**: Fast failures make it immediately clear where the problem occurred.
3. **Retry Logic**: FAILED tasks are retried, ensuring eventual consistency.
4. **No Silent Failures**: Every error is visible and logged.

## Implementation

### Layer 1: Indexers (Jira/Confluence)

**Fail-Fast Points:**
- ❌ Attachment download fails → Mark Jira issue/Confluence page as FAILED
- ❌ Image dimension extraction fails → Propagate exception
- ❌ Storage fails → Propagate exception

```kotlin
// FAIL-FAST: If any attachment download/storage fails, exception propagates
// This marks the entire Jira issue as FAILED for retry
for (att in visualAttachments) {
    val binaryData = downloadAttachment(att.downloadUrl, doc) // Can throw
    val (width, height) = extractImageDimensions(binaryData, att.mimeType) // Can throw
    val storagePath = directoryStructureService.storeAttachment(...) // Can throw
    // ... no try-catch here!
}
```

**Result:** If download/storage fails, the entire document (Jira issue/Confluence page) is marked as FAILED and will be retried.

### Layer 2: Qualifier Agent (Vision Subgraph)

**Phase 0 - Vision Augmentation:**

**Fail-Fast Points:**
- ❌ Load attachment fails → Task fails
- ❌ Vision model selection fails → Task fails
- ❌ Vision analysis fails → Task fails
- ❌ Augmentation fails → Task fails

```kotlin
// Node: Load Attachments
// FAIL-FAST: If any attachment fails to load, entire task fails
val loaded = visualAttachments.map { attachment ->
    loadAttachmentData(attachment, directoryStructureService) // Can throw
}

// Node: Process Vision
// FAIL-FAST: If ANY attachment vision analysis fails, entire task fails
for (attachment in attachments) {
    val visionModel = smartModelSelector.selectVisionModel(...) // Can throw
    val description = executeVisionAnalysis(...) // Can throw
    // ... no try-catch here!
}
```

**Result:** If vision processing fails, the entire PendingTask is marked as FAILED.

### Layer 3: Vision Execution

```kotlin
/**
 * Execute vision analysis using Koog Prompt API.
 *
 * FAIL-FAST: If vision analysis fails, exception propagates to caller.
 * This marks the entire task as FAILED (per docs design).
 */
suspend fun executeVisionAnalysis(...): String {
    val tempFile = Files.createTempFile(...)

    try {
        Files.write(tempFile, attachment.binaryData) // Can throw
        val prompt = Prompt.build(...) // Can throw
        val executor = promptExecutorFactory.getExecutor("OLLAMA") // Can throw
        val response = executor.execute(prompt, visionModel) // Can throw

        // FAIL-FAST: If model returns no text, throw exception
        return response.text ?: throw IllegalStateException("Vision model returned no text")
    } finally {
        // Best effort cleanup - don't fail task if cleanup fails
        try {
            Files.deleteIfExists(tempFile)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete temp file" }
        }
    }
}
```

**Note:** Only temp file cleanup uses try-catch, because cleanup failure shouldn't fail the task.

## Error Propagation Flow

```
Download Fails
  ↓
JiraContinuousIndexer throws exception
  ↓
indexIssue() catch block
  ↓
stateManager.markAsFailed(doc, error)
  ↓
Jira issue marked as FAILED in MongoDB
  ↓
Will be retried on next polling cycle
```

```
Vision Analysis Fails
  ↓
executeVisionAnalysis() throws exception
  ↓
nodeProcessVision propagates exception
  ↓
Koog Agent catches exception
  ↓
PendingTask marked as FAILED
  ↓
Will be retried
```

## Comparison: Fail-Fast vs Fail-Safe

### ❌ WRONG: Fail-Safe (what we DON'T do)

```kotlin
// BAD: Continue processing even if attachment fails
for (att in attachments) {
    try {
        processAttachment(att)
    } catch (e: Exception) {
        logger.warn("Skipping failed attachment") // Silent failure!
        // Continue with other attachments
    }
}
// Result: Partial success, incomplete vision data
```

### ✅ CORRECT: Fail-Fast (what we DO)

```kotlin
// GOOD: Fail immediately if attachment fails
for (att in attachments) {
    processAttachment(att) // Exception propagates
}
// Result: All or nothing - either all attachments succeed or task fails
```

## Benefits of Fail-Fast in Vision Augmentation

1. **Complete Data**: Either ALL attachments are processed or NONE. No partial states.
2. **Clear Errors**: Failed tasks are immediately visible in logs and can be debugged.
3. **Automatic Retry**: FAILED documents are automatically retried by the poller/indexer.
4. **No Data Corruption**: Incomplete vision analysis doesn't pollute RAG/Graph.
5. **Debugging**: Stack traces show exact failure point.

## Exception Types

### Retriable Errors (should eventually succeed):
- Network timeout downloading attachment
- Temporary Ollama server unavailability
- Temporary file system full

### Non-Retriable Errors (need manual fix):
- Corrupted image file
- Unsupported image format
- Missing attachment on Atlassian server
- Invalid connection credentials

Both types cause FAILED status, but non-retriable errors will keep failing on retry.

## Monitoring

Look for these patterns in logs:

```
// SUCCESS
🔍 VISION_PROCESS | file=screenshot.png | size=1920x1080
🔍 VISION_SUCCESS | file=screenshot.png | descriptionLength=1542

// FAILURE (fail-fast)
🔍 VISION_PROCESS | file=broken.png | size=1920x1080
ERROR Vision analysis failed: java.io.IOException: Corrupt PNG
FAILED indexing Jira issue: PROJ-123

// Result: Task marked as FAILED, will retry
```

## Summary

Vision Augmentation uses **FAIL-FAST design**:
- ✅ Exceptions propagate up
- ✅ No silent failures
- ✅ All-or-nothing processing
- ✅ Automatic retry via FAILED state
- ✅ Clear error visibility

This ensures **data integrity** and **debuggability** at the cost of potentially higher retry rates for transient errors.
