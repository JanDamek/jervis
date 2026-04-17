package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.Contract
import com.jervis.contracts.server.CreateFinancialRecordRequest
import com.jervis.contracts.server.CreateFinancialRecordResponse
import com.jervis.contracts.server.FinancialRecord
import com.jervis.contracts.server.GetFinancialSummaryRequest
import com.jervis.contracts.server.GetFinancialSummaryResponse
import com.jervis.contracts.server.ListContractsRequest
import com.jervis.contracts.server.ListContractsResponse
import com.jervis.contracts.server.ListFinancialRecordsRequest
import com.jervis.contracts.server.ListFinancialRecordsResponse
import com.jervis.contracts.server.ServerFinanceServiceGrpcKt
import com.jervis.finance.ContractDocument
import com.jervis.finance.FinancialDocument
import com.jervis.finance.FinancialService
import com.jervis.finance.FinancialStatus
import com.jervis.finance.FinancialType
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class ServerFinanceGrpcImpl(
    private val financialService: FinancialService,
) : ServerFinanceServiceGrpcKt.ServerFinanceServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun getSummary(
        request: GetFinancialSummaryRequest,
    ): GetFinancialSummaryResponse {
        val fromDate = request.fromDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        val toDate = request.toDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        val summary = financialService.getClientSummary(request.clientId, fromDate, toDate)
        return GetFinancialSummaryResponse
            .newBuilder()
            .setTotalIncome(summary.totalIncome)
            .setTotalExpenses(summary.totalExpenses)
            .setTotalPaymentsReceived(summary.totalPaymentsReceived)
            .setOutstandingInvoices(summary.outstandingInvoices)
            .setOverdueInvoices(summary.overdueInvoices)
            .setOverdueAmount(summary.overdueAmount)
            .setRecordCount(summary.recordCount)
            .build()
    }

    override suspend fun listRecords(
        request: ListFinancialRecordsRequest,
    ): ListFinancialRecordsResponse {
        val status = request.status.takeIf { it.isNotBlank() }?.let { FinancialStatus.valueOf(it) }
        val type = request.type.takeIf { it.isNotBlank() }?.let { FinancialType.valueOf(it) }
        val records = when {
            status != null -> financialService.listByClientAndStatus(request.clientId, status)
            type != null -> financialService.listByClientAndType(request.clientId, type)
            else -> financialService.listByClient(request.clientId)
        }
        val builder = ListFinancialRecordsResponse.newBuilder()
        records.forEach { builder.addRecords(it.toProto()) }
        return builder.build()
    }

    override suspend fun createRecord(
        request: CreateFinancialRecordRequest,
    ): CreateFinancialRecordResponse {
        val doc = FinancialDocument(
            clientId = request.clientId,
            projectId = request.projectId.takeIf { it.isNotBlank() },
            type = FinancialType.valueOf(request.type),
            amount = request.amount,
            currency = request.currency.ifBlank { "CZK" },
            amountCzk = if (request.amountCzk == 0.0) request.amount else request.amountCzk,
            vatRate = request.vatRate.takeIf { it != 0.0 },
            vatAmount = request.vatAmount.takeIf { it != 0.0 },
            invoiceNumber = request.invoiceNumber.takeIf { it.isNotBlank() },
            variableSymbol = request.variableSymbol.takeIf { it.isNotBlank() },
            counterpartyName = request.counterpartyName.takeIf { it.isNotBlank() },
            counterpartyIco = request.counterpartyIco.takeIf { it.isNotBlank() },
            counterpartyAccount = request.counterpartyAccount.takeIf { it.isNotBlank() },
            issueDate = request.issueDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
            dueDate = request.dueDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
            paymentDate = request.paymentDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
            sourceUrn = request.sourceUrn.ifBlank { "orchestrator" },
            description = request.description,
        )
        val saved = financialService.createFinancialRecord(doc)
        logger.info { "FINANCE_RECORD_CREATED | id=${saved.id.toHexString()} type=${saved.type} client=${saved.clientId}" }
        return CreateFinancialRecordResponse
            .newBuilder()
            .setId(saved.id.toHexString())
            .setMatched(saved.status == FinancialStatus.MATCHED)
            .build()
    }

    override suspend fun listContracts(
        request: ListContractsRequest,
    ): ListContractsResponse {
        val clientId = request.clientId.takeIf { it.isNotBlank() }
        val contracts = when {
            request.activeOnly -> financialService.listActiveContracts(clientId)
            clientId != null -> financialService.listContracts(clientId)
            else -> financialService.listActiveContracts(null)
        }
        val builder = ListContractsResponse.newBuilder()
        contracts.forEach { builder.addContracts(it.toProto()) }
        return builder.build()
    }

    private fun FinancialDocument.toProto(): FinancialRecord =
        FinancialRecord.newBuilder()
            .setId(id.toHexString())
            .setType(type.name)
            .setAmount(amount)
            .setCurrency(currency)
            .setAmountCzk(amountCzk)
            .setInvoiceNumber(invoiceNumber.orEmpty())
            .setVariableSymbol(variableSymbol.orEmpty())
            .setCounterpartyName(counterpartyName.orEmpty())
            .setStatus(status.name)
            .setIssueDate(issueDate?.toString().orEmpty())
            .setDueDate(dueDate?.toString().orEmpty())
            .setDescription(description)
            .build()

    private fun ContractDocument.toProto(): Contract =
        Contract.newBuilder()
            .setId(id.toHexString())
            .setClientId(clientId)
            .setCounterparty(counterparty)
            .setType(type.name)
            .setRate(rate)
            .setRateUnit(rateUnit.name)
            .setCurrency(currency)
            .setStartDate(startDate.toString())
            .setEndDate(endDate?.toString().orEmpty())
            .setStatus(status.name)
            .build()
}
