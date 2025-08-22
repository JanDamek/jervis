package com.jervis.repository.mongo

import com.jervis.entity.mongo.PlanDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PlanMongoRepository : CoroutineCrudRepository<PlanDocument, String>
