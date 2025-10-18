package com.jervis.configuration.prompts

import com.jervis.domain.model.ModelType

data class ModelParams(
    var modelType: ModelType,
    var creativityLevel: CreativityLevel,
)
