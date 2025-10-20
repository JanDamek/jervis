package com.jervis.domain.background

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Checkpoint {
    val kind: String

    @Serializable
    @SerialName("DocumentScan")
    data class DocumentScan(
        val documentId: String,
        val lastOffset: Int,
        val totalChunks: Int,
        override val kind: String = "DocumentScan",
    ) : Checkpoint

    @Serializable
    @SerialName("CodeAnalysis")
    data class CodeAnalysis(
        val lastFile: String?,
        val remainingFiles: List<String>,
        override val kind: String = "CodeAnalysis",
    ) : Checkpoint

    @Serializable
    @SerialName("ThreadClustering")
    data class ThreadClustering(
        val processedThreadIds: Set<String>,
        val state: Map<String, List<String>>,
        override val kind: String = "ThreadClustering",
    ) : Checkpoint

    @Serializable
    @SerialName("Generic")
    data class Generic(
        val cursor: String?,
        val notes: String?,
        override val kind: String = "Generic",
    ) : Checkpoint
}
