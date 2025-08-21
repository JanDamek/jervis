package com.jervis.repository.mongo

import com.jervis.entity.mongo.ClientDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ClientMongoRepository : CoroutineCrudRepository<ClientDocument, String>
