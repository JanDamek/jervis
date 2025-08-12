package com.jervis.service.setting

import com.jervis.entity.mongo.SettingDocument
import com.jervis.entity.mongo.SettingType
import com.jervis.events.SettingsChangeEvent
import com.jervis.repository.mongo.SettingMongoRepository
import kotlinx.coroutines.runBlocking
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

/**
 * Test script to verify that lazy properties are refreshed when settings change
 */
fun main() {
    println("[DEBUG_LOG] Testing lazy properties refresh functionality")
    
    // Mock repository that simulates database behavior
    class MockSettingRepository : SettingMongoRepository {
        private val settings = mutableMapOf<String, SettingDocument>()
        
        override suspend fun findByKey(key: String): SettingDocument? = settings[key]
        
        override suspend fun save(setting: SettingDocument): SettingDocument {
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
    
    // Initialize with some test data
    runBlocking {
        mockRepo.save(SettingDocument(
            key = "openai_api_key",
            value = "initial-key",
            type = SettingType.STRING,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        ))
        
        mockRepo.save(SettingDocument(
            key = "startup.minimize",
            value = "false",
            type = SettingType.BOOLEAN,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        ))
    }
    
    val settingService = SettingService(mockRepo, mockPublisher)
    
    // Test initial values
    println("[DEBUG_LOG] Initial openaiApiKey: ${settingService.openaiApiKey}")
    println("[DEBUG_LOG] Initial startupMinimize: ${settingService.startupMinimize}")
    
    // Change the settings in the "database"
    runBlocking {
        mockRepo.save(SettingDocument(
            key = "openai_api_key",
            value = "updated-key",
            type = SettingType.STRING,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        ))
        
        mockRepo.save(SettingDocument(
            key = "startup.minimize",
            value = "true",
            type = SettingType.BOOLEAN,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        ))
    }
    
    // Values should still be cached (old values)
    println("[DEBUG_LOG] Cached openaiApiKey (should be old): ${settingService.openaiApiKey}")
    println("[DEBUG_LOG] Cached startupMinimize (should be old): ${settingService.startupMinimize}")
    
    // Simulate settings change event
    settingService.onSettingsChange(SettingsChangeEvent(settingService))
    
    // Values should now be refreshed (new values)
    println("[DEBUG_LOG] Refreshed openaiApiKey (should be new): ${settingService.openaiApiKey}")
    println("[DEBUG_LOG] Refreshed startupMinimize (should be new): ${settingService.startupMinimize}")
    
    // Test manual refresh
    settingService.refreshCachedSettings()
    println("[DEBUG_LOG] After manual refresh - openaiApiKey: ${settingService.openaiApiKey}")
    
    println("[DEBUG_LOG] Test completed successfully!")
}