package com.jervis.koog

import com.jervis.orchestrator.OrchestratorAgent
import org.springframework.stereotype.Service

@Service
class OllamaProviderSelector(
    private val orchestratorAgent: OrchestratorAgent,
) {
    // In a real scenario, this would involve checking a queue, a lock,
    // or some other mechanism to determine if the P40 is busy.
    // For now, we'll just simulate it.
    private fun isP40Busy(): Boolean = orchestratorAgent.isProviderInUse("OLLAMA")

    fun getProvider(): String =
        if (isP40Busy()) {
            "OLLAMA_QUALIFIER"
        } else {
            "OLLAMA"
        }
}
