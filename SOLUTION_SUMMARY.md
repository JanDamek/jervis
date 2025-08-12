# Solution: Refreshable Lazy Properties in SettingService

## Problem
The original issue (in Czech): "Existuje cesta jak by lazy po zmene prenacist? Jedna se o tridu SettingService"
Translation: "Is there a way to reload lazy properties after a change? It's about the SettingService class"

The SettingService class had lazy properties that were initialized once and never refreshed when the underlying settings changed in the database.

## Original Implementation
```kotlin
val openaiApiKey: String by lazy { runBlocking { getOpenaiApiKey() } }
val startupMinimize: Boolean by lazy { getStartupMinimize() }
val lmStudioUrlLazy: String by lazy { runBlocking { getLmStudioUrl() } }
val ollamaUrlLazy: String by lazy { runBlocking { getOllamaUrl() } }
val embeddingModelNameLazy: String by lazy { runBlocking { getEmbeddingModelName() } }
```

## Solution Implementation

### 1. Added Caching Mechanism
```kotlin
// Cache for frequently accessed settings that can be refreshed
private val settingsCache = ConcurrentHashMap<String, Any>()
```

### 2. Replaced Lazy Properties with Cached Properties
```kotlin
val openaiApiKey: String
    get() = settingsCache.computeIfAbsent("openaiApiKey") { 
        runBlocking { getOpenaiApiKey() } 
    } as String

val startupMinimize: Boolean
    get() = settingsCache.computeIfAbsent("startupMinimize") { 
        getStartupMinimize() 
    } as Boolean

val lmStudioUrlLazy: String
    get() = settingsCache.computeIfAbsent("lmStudioUrl") {
        runBlocking { getLmStudioUrl() }
    } as String

val ollamaUrlLazy: String
    get() = settingsCache.computeIfAbsent("ollamaUrl") {
        runBlocking { getOllamaUrl() }
    } as String

val embeddingModelNameLazy: String
    get() = settingsCache.computeIfAbsent("embeddingModelName") {
        runBlocking { getEmbeddingModelName() }
    } as String
```

### 3. Added Refresh Functionality
```kotlin
/**
 * Refreshes all cached settings values
 * This method is called automatically when settings change
 */
fun refreshCachedSettings() {
    settingsCache.clear()
}

/**
 * Event listener that refreshes cached settings when settings change
 */
@EventListener
fun onSettingsChange(event: SettingsChangeEvent) {
    refreshCachedSettings()
}
```

### 4. Added Required Imports
```kotlin
import org.springframework.context.event.EventListener
import java.util.concurrent.ConcurrentHashMap
```

## How It Works

1. **Lazy Initialization**: Properties are still lazily initialized on first access using `computeIfAbsent`
2. **Caching**: Values are cached in a thread-safe `ConcurrentHashMap`
3. **Automatic Refresh**: When any setting is saved via `saveValue()`, a `SettingsChangeEvent` is published
4. **Event Handling**: The `@EventListener` method automatically clears the cache when settings change
5. **Manual Refresh**: The `refreshCachedSettings()` method can be called manually if needed

## Benefits

1. **Thread-Safe**: Uses `ConcurrentHashMap` for thread-safe caching
2. **Automatic**: No manual intervention needed - properties refresh automatically when settings change
3. **Performance**: Still provides lazy initialization and caching benefits
4. **Backward Compatible**: Property access syntax remains the same
5. **Manual Control**: Provides manual refresh capability when needed

## Usage

The properties can be used exactly as before:
```kotlin
val apiKey = settingService.openaiApiKey
val minimize = settingService.startupMinimize
val lmUrl = settingService.lmStudioUrlLazy
```

When settings change through any of the `saveValue()`, `saveBooleanSetting()`, or `saveIntSetting()` methods, the cached properties will automatically refresh on next access.

For manual refresh:
```kotlin
settingService.refreshCachedSettings()
```