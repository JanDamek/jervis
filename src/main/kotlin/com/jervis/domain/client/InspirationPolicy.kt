package com.jervis.domain.client

data class InspirationPolicy(
    val allowCrossClientInspiration: Boolean = true,
    val allowedClientSlugs: List<String> = emptyList(),
    val disallowedClientSlugs: List<String> = emptyList(),
    val enforceFullAnonymization: Boolean = true,
    val maxSnippetsPerForeignClient: Int = 5,
)
