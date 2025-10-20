package com.jervis.domain.background

import kotlinx.serialization.Serializable

@Serializable
data class TargetRef(
    val type: TargetRefType,
    val id: String,
)

enum class TargetRefType {
    DOC,
    CODE,
    THREAD,
    PROJECT,
    CLIENT,
}
