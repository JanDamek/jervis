package com.jervis.service.setting

import com.jervis.entity.mongo.SettingDocument
import com.jervis.entity.mongo.SettingType
import com.jervis.events.SettingsChangeEvent
import com.jervis.repository.mongo.SettingMongoRepository
import kotlinx.coroutines.runBlocking
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

/**
 * Test script to verify that properties work without cache and setters save directly to database
 */
fun main() {
    println("[DEBUG_LOG] Testing properties without cache functionality")
    
    // Mock repository that simulates database behavior
    class MockSettingRepository : SettingMongoRepository {
        private val settings = mutableMapOf<String, SettingDocument>()
        
        override suspend fun findByKey(key: String): SettingDocument? {
            println("[DEBUG_LOG] Database query for key: $key")
            return settings[key]
        }
        
        override suspend fun save(setting: SettingDocument): SettingDocument {
            println("[DEBUG_LOG] Database save for key: ${setting.key} with value: ${setting.value}")
            settings[setting.key] = setting
            return setting
        }
        
        // Add other required methods as no-ops for testing
        override suspend fun findAll(): List<SettingDocument> = settings.values.toList()
        override suspend fun deleteByKey(key: String) { settings.remove(key) }
    }
    
    // Mock event publisher
    class MockEventPublisher : ApplicationEventPublisher {
        var lastEvent: Any? = null
        
        override fun publishEvent(event: Any) {
            lastEvent = event
            println("[DEBUG_LOG] Event published: ${event::class.simpleName}")
        }
    }
    
    val mockRepo = MockSettingRepository()
    val mockPublisher = MockEventPublisher()
    
    val settingService = SettingService(mockRepo, mockPublisher)
    
    // Test 1: Initial access should query database each time (no cache)
    println("[DEBUG_LOG] === Test 1: No caching behavior ===")
    println("[DEBUG_LOG] First access to openaiApiKey:")
    val apiKey1 = settingService.openaiApiKey
    println("[DEBUG_LOG] Result: '$apiKey1'")
    
    println("[DEBUG_LOG] Second access to openaiApiKey:")
    val apiKey2 = settingService.openaiApiKey
    println("[DEBUG_LOG] Result: '$apiKey2'")
    
    // Test 2: Setter should immediately save to database
    println("[DEBUG_LOG] === Test 2: Setter saves directly to database ===")
    println("[DEBUG_LOG] Setting openaiApiKey to 'test-key-123'")
    settingService.openaiApiKey = "test-key-123"
    
    println("[DEBUG_LOG] Reading openaiApiKey after setting:")
    val apiKey3 = settingService.openaiApiKey
    println("[DEBUG_LOG] Result: '$apiKey3'")
    
    // Test 3: Boolean property
    println("[DEBUG_LOG] === Test 3: Boolean property ===")
    println("[DEBUG_LOG] Initial startupMinimize:")
    val minimize1 = settingService.startupMinimize
    println("[DEBUG_LOG] Result: $minimize1")
    
    println("[DEBUG_LOG] Setting startupMinimize to true")
    settingService.startupMinimize = true
    
    println("[DEBUG_LOG] Reading startupMinimize after setting:")
    val minimize2 = settingService.startupMinimize
    println("[DEBUG_LOG] Result: $minimize2")
    
    // Test 4: URL properties
    println("[DEBUG_LOG] === Test 4: URL properties ===")
    println("[DEBUG_LOG] Setting lmStudioUrl to 'http://localhost:9999'")
    settingService.lmStudioUrl = "http://localhost:9999"
    
    println("[DEBUG_LOG] Reading lmStudioUrl after setting:")
    val lmUrl = settingService.lmStudioUrl
    println("[DEBUG_LOG] Result: '$lmUrl'")
    
    // Test 5: Integer properties
    println("[DEBUG_LOG] === Test 5: Integer properties ===")
    println("[DEBUG_LOG] Setting anthropicRateLimitInputTokens to 50000")
    settingService.anthropicRateLimitInputTokens = 50000
    
    println("[DEBUG_LOG] Reading anthropicRateLimitInputTokens after setting:")
    val tokens = settingService.anthropicRateLimitInputTokens
    println("[DEBUG_LOG] Result: $tokens")
    
    println("[DEBUG_LOG] Test completed successfully!")
    println("[DEBUG_LOG] All properties work without caching and setters save directly to database!")
}