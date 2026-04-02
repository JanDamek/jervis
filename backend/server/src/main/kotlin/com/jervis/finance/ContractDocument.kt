package com.jervis.finance

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.time.LocalDate

/**
 * Contract record — employment, freelance, service agreements.
 *
 * Tracks active contracts for financial projections and capacity planning.
 */
@Document(collection = "contracts")
@CompoundIndex(def = "{'clientId': 1, 'status': 1}")
data class ContractDocument(
    @Id
    val id: ObjectId = ObjectId(),
    val clientId: String,
    val projectId: String? = null,
    val counterparty: String,
    val type: ContractType,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val rate: Double,
    val rateUnit: RateUnit,
    val currency: String = "CZK",
    val terms: String = "",
    val status: ContractStatus = ContractStatus.ACTIVE,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class ContractType {
    EMPLOYMENT,
    FREELANCE,
    SERVICE,
}

enum class RateUnit {
    HOUR,
    DAY,
    MONTH,
}

enum class ContractStatus {
    ACTIVE,
    EXPIRED,
    TERMINATED,
}
