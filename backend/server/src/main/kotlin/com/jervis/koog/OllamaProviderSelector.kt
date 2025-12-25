package com.jervis.koog

import org.springframework.stereotype.Service

@Service
class OllamaProviderSelector(
    private val koogWorkflowAgent: KoogWorkflowAgent,
) {
    // In a real scenario, this would involve checking a queue, a lock,
    // or some other mechanism to determine if the P40 is busy.
    // For now, we'll just simulate it.
    private fun isP40Busy(): Boolean = koogWorkflowAgent.isProviderInUse("OLLAMA")

    fun getProvider(): String =
        if (isP40Busy()) {
            "OLLAMA_QUALIFIER"
        } else {
            "OLLAMA"
        }
}
