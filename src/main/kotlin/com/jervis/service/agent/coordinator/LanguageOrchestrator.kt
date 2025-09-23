package com.jervis.service.agent.coordinator

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.core.LlmGateway
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LanguageOrchestrator(
    private val llmGateway: LlmGateway,
) {
    private val logger = LoggerFactory.getLogger(LanguageOrchestrator::class.java)

    suspend fun translate(
        text: String,
        quick: Boolean,
    ): DetectionResult {
        val result = llmGateway
            .callLlm(
                type = PromptTypeEnum.QUESTION_INTERPRETER,
                userPrompt = text,
                quick = quick,
                responseSchema = DetectionResult(),
            )

        // Validate checklist quality - check for compound questions
        validateChecklistQuality(result.questionChecklist, text)
        
        return result
    }

    private fun validateChecklistQuality(questionChecklist: List<String>, originalText: String) {
        val compoundIndicators = listOf(" and ", " or ", ",", ";", "?", "what", "where", "when", "why", "how", "which")
        
        questionChecklist.forEach { question ->
            val lowercaseQuestion = question.lowercase()
            val suspiciousPatterns = mutableListOf<String>()
            
            // Check for conjunctions and multiple question words
            if (lowercaseQuestion.contains(" and ")) suspiciousPatterns.add("contains 'and'")
            if (lowercaseQuestion.contains(" or ")) suspiciousPatterns.add("contains 'or'")
            if (lowercaseQuestion.count { it == ',' } > 0) suspiciousPatterns.add("contains commas")
            
            // Check for multiple question words indicating compound questions
            val questionWords = listOf("what", "where", "when", "why", "how", "which")
            val questionWordCount = questionWords.count { lowercaseQuestion.contains(it) }
            if (questionWordCount > 1) suspiciousPatterns.add("multiple question words ($questionWordCount)")
            
            // Check for multiple verbs (simple heuristic)
            val verbPatterns = listOf(" is ", " are ", " does ", " do ", " has ", " have ", " can ", " will ", " should ")
            val verbCount = verbPatterns.count { lowercaseQuestion.contains(it) }
            if (verbCount > 1) suspiciousPatterns.add("multiple verbs ($verbCount)")
            
            if (suspiciousPatterns.isNotEmpty()) {
                logger.warn(
                    "QUESTION_INTERPRETER validation warning: Checklist item may be compound and should be split: '{}' - Issues: {} - Original text: '{}'",
                    question,
                    suspiciousPatterns.joinToString(", "),
                    originalText
                )
            }
        }
    }

    @Serializable
    data class DetectionResult(
        val englishText: String = "",
        val originalLanguage: String = "",
        val contextName: String = "New Context",
        val questionChecklist: List<String> = emptyList(),
    )
}
