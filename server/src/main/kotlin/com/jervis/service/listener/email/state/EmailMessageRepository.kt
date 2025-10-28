package com.jervis.service.listener.email.state

import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface EmailMessageRepository : ReactiveMongoRepository<EmailMessageDocument, ObjectId> {
    fun findByAccountId(accountId: ObjectId): Flux<EmailMessageDocument>

    fun findByAccountIdAndStateOrderByReceivedAtAsc(
        accountId: ObjectId,
        state: EmailMessageState,
    ): Flux<EmailMessageDocument>

    fun findByAccountIdAndMessageId(
        accountId: ObjectId,
        messageId: String,
    ): Mono<EmailMessageDocument>
}
