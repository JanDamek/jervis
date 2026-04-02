package com.jervis.service.finance

import com.jervis.dto.finance.ContractCreateDto
import com.jervis.dto.finance.ContractDto
import com.jervis.dto.finance.ContractUpdateDto
import com.jervis.dto.finance.FinancialRecordCreateDto
import com.jervis.dto.finance.FinancialRecordDto
import com.jervis.dto.finance.FinancialSummaryDto
import kotlinx.rpc.annotations.Rpc

/**
 * Financial management service — invoices, payments, contracts, reports.
 */
@Rpc
interface IFinancialService {

    // ── Financial Records ──

    suspend fun createFinancialRecord(request: FinancialRecordCreateDto): FinancialRecordDto

    suspend fun getFinancialRecord(id: String): FinancialRecordDto?

    suspend fun listFinancialRecords(clientId: String, status: String? = null, type: String? = null): List<FinancialRecordDto>

    suspend fun updateFinancialStatus(id: String, status: String): FinancialRecordDto?

    suspend fun deleteFinancialRecord(id: String): Boolean

    // ── Reports ──

    suspend fun getFinancialSummary(clientId: String, fromDate: String? = null, toDate: String? = null): FinancialSummaryDto

    suspend fun detectOverdueInvoices(): Int

    // ── Contracts ──

    suspend fun createContract(request: ContractCreateDto): ContractDto

    suspend fun listContracts(clientId: String, activeOnly: Boolean = false): List<ContractDto>

    suspend fun updateContract(request: ContractUpdateDto): ContractDto?

    suspend fun deleteContract(id: String): Boolean
}
