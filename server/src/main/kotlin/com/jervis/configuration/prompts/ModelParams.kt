package com.jervis.configuration.prompts

import com.jervis.domain.model.ModelTypeEnum

data class ModelParams(
    var modelType: ModelTypeEnum,
    var creativityLevel: CreativityLevel,
)
