# Bug: Chat subscription logs client disconnect as ERROR

**Priority**: LOW
**Area**: Server → `ChatRpcImpl.kt`

## Problem

When client disconnects during chat subscription (e.g. app reconnect), server logs:

```
ERROR com.jervis.rpc.ChatRpcImpl - Failed to load chat history for subscription
java.util.concurrent.CancellationException: Request cancelled by client
```

This is normal behavior during app reconnect, not an actual error. It pollutes error logs.

## Fix

Catch `CancellationException` separately and log at WARN or DEBUG level:

```kotlin
} catch (e: CancellationException) {
    logger.debug { "Chat subscription cancelled by client (reconnect)" }
} catch (e: Exception) {
    logger.error(e) { "Failed to load chat history for subscription" }
}
```
