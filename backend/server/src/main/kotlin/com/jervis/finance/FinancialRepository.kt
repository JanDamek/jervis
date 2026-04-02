package com.jervis.finance

import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface FinancialRepository : CoroutineCrudRepository<FinancialDocument, ObjectId> {

    fun findByClientId(clientId: String): Flow<FinancialDocument>

    fun findByClientIdAndStatus(clientId: String, status: FinancialStatus): Flow<FinancialDocument>

    fun findByClientIdAndType(clientId: String, type: FinancialType): Flow<FinancialDocument>

    fun findByVariableSymbol(variableSymbol: String): Flow<FinancialDocument>

    fun findByStatusAndDueDateBefore(status: FinancialStatus, date: LocalDate): Flow<FinancialDocument>

    fun findByClientIdAndProjectId(clientId: String, projectId: String): Flow<FinancialDocument>
}

@Repository
interface ContractRepository : CoroutineCrudRepository<ContractDocument, ObjectId> {

    fun findByClientId(clientId: String): Flow<ContractDocument>

    fun findByClientIdAndStatus(clientId: String, status: ContractStatus): Flow<ContractDocument>

    fun findByStatus(status: ContractStatus): Flow<ContractDocument>
}
