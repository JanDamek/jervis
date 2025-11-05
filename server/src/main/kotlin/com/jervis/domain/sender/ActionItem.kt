package com.jervis.domain.sender

import java.time.Instant

data class ActionItem(
    val description: String,
    val assignedTo: String?,
    val deadline: Instant?,
    val completed: Boolean,
    val createdAt: Instant,
)
