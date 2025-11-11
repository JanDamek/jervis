package com.jervis.service.text

import org.springframework.stereotype.Service

/**
 * Service for normalizing text before chunking and embedding.
 *
 * Ensures consistent text processing across all RAG sources (EMAIL, GIT, CONFLUENCE, JIRA, etc.).
 *
 * Normalization includes:
 * - Converting escape sequences (\r\n, \n, \t) to actual whitespace
 * - Normalizing multiple consecutive whitespace characters
 * - Trimming leading/trailing whitespace
 * - Removing empty lines with only whitespace
 * - Converting null or blank strings to empty strings
 *
 * This service follows SOLID principles:
 * - Single Responsibility: Only handles text normalization
 * - Open/Closed: Extensible for additional normalization rules
 * - Dependency Inversion: All indexers depend on this abstraction
 */
@Service
class TextNormalizationService {

    /**
     * Normalize text for consistent embedding and storage.
     *
     * @param text Raw text to normalize
     * @return Normalized text ready for chunking and embedding
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) {
            return ""
        }

        return text
            // Convert escape sequences to actual characters
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            // Normalize line breaks (Windows, Unix, old Mac)
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            // Remove lines that contain only whitespace
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            // Normalize multiple spaces to single space (within lines)
            .replace(Regex(" {2,}"), " ")
            // Normalize multiple tabs to single space
            .replace(Regex("\t+"), " ")
            // Remove multiple consecutive newlines (keep max 2 for paragraph separation)
            .replace(Regex("\n{3,}"), "\n\n")
            // Final trim
            .trim()
    }

    /**
     * Normalize text but preserve some formatting for code blocks.
     * Useful for Git diffs and code content.
     *
     * @param text Raw text to normalize
     * @return Normalized text with preserved code formatting
     */
    fun normalizePreservingCode(text: String?): String {
        if (text.isNullOrBlank()) {
            return ""
        }

        return text
            // Convert escape sequences to actual characters
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            // Normalize line breaks
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            // Don't remove whitespace-only lines in code
            // Just normalize multiple consecutive newlines
            .replace(Regex("\n{4,}"), "\n\n\n")
            // Final trim
            .trim()
    }
}
