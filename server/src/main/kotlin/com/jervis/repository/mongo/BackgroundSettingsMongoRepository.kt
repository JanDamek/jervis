package com.jervis.repository.mongo

import com.jervis.entity.mongo.BackgroundSettingsDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface BackgroundSettingsMongoRepository : CoroutineCrudRepository<BackgroundSettingsDocument, String>
