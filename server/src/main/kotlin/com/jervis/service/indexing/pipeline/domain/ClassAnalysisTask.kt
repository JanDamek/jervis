package com.jervis.service.indexing.pipeline.domain

/**
 * Pipeline task for class analysis processing
 */
data class ClassAnalysisTask(
    val analysisItem: JoernAnalysisItem,
    val classSymbol: JoernSymbol,
)
