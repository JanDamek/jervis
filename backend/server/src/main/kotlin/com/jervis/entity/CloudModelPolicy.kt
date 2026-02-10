package com.jervis.entity

data class CloudModelPolicy(
    val autoUseAnthropic: Boolean = false,
    val autoUseOpenai: Boolean = false,
    val autoUseGemini: Boolean = false,
)
