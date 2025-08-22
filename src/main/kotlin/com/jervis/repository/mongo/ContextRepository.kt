package com.jervis.repository.mongo

import com.jervis.entity.mongo.ContextDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ContextMongoRepository : CoroutineCrudRepository<ContextDocument, String>
