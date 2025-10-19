package com.jervis.service.indexing.monitoring

/**
 * Enum representing all available indexing steps with their metadata
 */
enum class IndexingStepTypeEnum(
    val stepName: String,
    val description: String,
) {
    CODE_FILES("Code Files", "Indexing source code files"),
    TEXT_CONTENT("Text Content", "Indexing documentation and text files"),
    JOERN_ANALYSIS("Joern Analysis", "Running Joern code analysis"),
    GIT_HISTORY("Git History", "Indexing git commit history"),
    DEPENDENCIES("Dependencies", "Analyzing project dependencies"),
    CLASS_SUMMARIES("Class Summaries", "Generating class summaries"),
    COMPREHENSIVE_FILES("Comprehensive Files", "Deep analysis of source files"),
    DOCUMENTATION("Documentation", "Processing project documentation"),
    MEETING_TRANSCRIPTS("Meeting Transcripts", "Indexing meeting transcripts"),
    AUDIO_TRANSCRIPTS("Audio Transcripts", "Indexing audio transcripts"),
    CLIENT_UPDATE("Client Update", "Updating client descriptions"),
    PROJECT("Project Indexing", "Overall project indexing process"),
}
