package com.jervis.domain.storage

/**
 * Represents types of subdirectories within a project
 */
enum class ProjectSubdirectoryEnum(
    val dirName: String,
) {
    GIT("git"),
    UPLOADS("uploads"),
    AUDIO("audio"),
    DOCUMENTS("documents"),
    MEETINGS("meetings"),
}
