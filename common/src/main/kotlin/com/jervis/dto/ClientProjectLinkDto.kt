package com.jervis.dto

import com.jervis.common.Constants
import kotlinx.serialization.Serializable

@Serializable
data class ClientProjectLinkDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val clientId: String = Constants.GLOBAL_ID_STRING,
    val projectId: String = Constants.GLOBAL_ID_STRING,
    val isDisabled: Boolean = false,
    val anonymizationEnabled: Boolean = true,
    val historical: Boolean = false,
)
