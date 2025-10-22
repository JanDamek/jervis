package com.jervis.repository.mongo

import com.jervis.entity.mongo.EmailAccountDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface EmailAccountMongoRepository : ReactiveMongoRepository<EmailAccountDocument, ObjectId> {
    fun findByClientId(clientId: ObjectId): Flux<EmailAccountDocument>

    fun findByProjectId(projectId: ObjectId): Flux<EmailAccountDocument>

    fun findFirstByIsActiveTrueOrderByLastIndexedAtAscCreatedAtAsc(): Mono<EmailAccountDocument>
}
