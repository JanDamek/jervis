package com.jervis.domain.confluence

import java.time.Instant

data class ActionItemEmbedded(
    val description: String,
    val assignedTo: String? = null,
    val deadline: Instant? = null,
    val completed: Boolean = false,
    val createdAt: Instant = Instant.now(),
)
