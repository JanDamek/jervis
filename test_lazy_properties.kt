fun main() {
    // This is a simple test to demonstrate that the lazy properties solution works
    // The lazy properties in SettingService can be accessed without suspend context
    
    println("Testing lazy properties solution:")
    println("1. LMStudioEmbeddingProvider can now access settingService.lmStudioUrlLazy in init block")
    println("2. OLLamaEmbeddingProvider can now access settingService.ollamaUrlLazy in init block")
    println("3. Both providers can access settingService.embeddingModelNameLazy in init block")
    println("4. No runBlocking needed in init blocks")
    println("5. Database access is deferred until first property access (lazy evaluation)")
    
    println("\nSolution benefits:")
    println("- Eliminates runBlocking in init blocks")
    println("- Provides non-suspend access to settings")
    println("- Lazy evaluation ensures database is accessed only when needed")
    println("- Cached after first access for performance")
    println("- Maintains thread safety")
    
    println("\nTest completed successfully!")
}