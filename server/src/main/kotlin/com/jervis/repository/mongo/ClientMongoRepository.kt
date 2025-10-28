package com.jervis.repository.mongo

import com.jervis.entity.ClientDocument
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ClientMongoRepository : CoroutineCrudRepository<ClientDocument, ObjectId>
