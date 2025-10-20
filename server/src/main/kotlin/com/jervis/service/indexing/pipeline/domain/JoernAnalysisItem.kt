package com.jervis.service.indexing.pipeline.domain

import org.bson.types.ObjectId
import java.nio.file.Path

/**
 * Pipeline item representing a Joern analysis result
 */
data class JoernAnalysisItem(
    val filePath: Path,
    val symbol: JoernSymbol,
    val projectId: ObjectId,
    val workerId: Int,
    val timestamp: Long,
)
