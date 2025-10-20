package com.jervis.repository.mongo

import com.jervis.domain.authentication.ServiceTypeEnum
import com.jervis.entity.mongo.ServiceCredentialsDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

@Repository
interface ServiceCredentialsMongoRepository : ReactiveMongoRepository<ServiceCredentialsDocument, ObjectId> {
    fun findByClientIdAndProjectIdAndServiceTypeEnum(
        clientId: ObjectId,
        projectId: ObjectId,
        serviceTypeEnum: ServiceTypeEnum,
    ): Flux<ServiceCredentialsDocument>

    fun findByClientIdAndServiceTypeEnum(
        clientId: ObjectId,
        serviceTypeEnum: ServiceTypeEnum,
    ): Flux<ServiceCredentialsDocument>

    fun findByClientIdAndProjectIdIsNullAndServiceTypeEnum(
        clientId: ObjectId,
        serviceTypeEnum: ServiceTypeEnum,
    ): Flux<ServiceCredentialsDocument>

    fun findByProjectIdAndServiceTypeEnum(
        projectId: ObjectId,
        serviceTypeEnum: ServiceTypeEnum,
    ): Flux<ServiceCredentialsDocument>
}
