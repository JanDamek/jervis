package com.jervis.finance

import com.jervis.dto.finance.*
import com.jervis.service.finance.IFinancialService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

@Component
class FinancialRpcImpl(
    private val financialService: FinancialService,
) : IFinancialService {

    override suspend fun createFinancialRecord(request: FinancialRecordCreateDto): FinancialRecordDto {
        val doc = FinancialDocument(
            clientId = request.clientId,
            projectId = request.projectId,
            type = FinancialType.valueOf(request.type.name),
            amount = request.amount,
            currency = request.currency,
            amountCzk = request.amountCzk ?: request.amount,
            vatRate = request.vatRate,
            vatAmount = request.vatAmount,
            invoiceNumber = request.invoiceNumber,
            variableSymbol = request.variableSymbol,
            counterpartyName = request.counterpartyName,
            counterpartyIco = request.counterpartyIco,
            counterpartyAccount = request.counterpartyAccount,
            issueDate = request.issueDate?.let { LocalDate.parse(it) },
            dueDate = request.dueDate?.let { LocalDate.parse(it) },
            paymentDate = request.paymentDate?.let { LocalDate.parse(it) },
            sourceUrn = request.sourceUrn,
            description = request.description,
        )
        return financialService.createFinancialRecord(doc).toDto()
    }

    override suspend fun getFinancialRecord(id: String): FinancialRecordDto? {
        return financialService.getFinancialRecord(id)?.toDto()
    }

    override suspend fun listFinancialRecords(clientId: String, status: String?, type: String?): List<FinancialRecordDto> {
        val records = when {
            status != null -> financialService.listByClientAndStatus(clientId, FinancialStatus.valueOf(status))
            type != null -> financialService.listByClientAndType(clientId, FinancialType.valueOf(type))
            else -> financialService.listByClient(clientId)
        }
        return records.map { it.toDto() }
    }

    override suspend fun updateFinancialStatus(id: String, status: String): FinancialRecordDto? {
        return financialService.updateStatus(id, FinancialStatus.valueOf(status))?.toDto()
    }

    override suspend fun deleteFinancialRecord(id: String): Boolean {
        return financialService.deleteFinancialRecord(id)
    }

    override suspend fun getFinancialSummary(clientId: String, fromDate: String?, toDate: String?): FinancialSummaryDto {
        val summary = financialService.getClientSummary(
            clientId = clientId,
            fromDate = fromDate?.let { LocalDate.parse(it) },
            toDate = toDate?.let { LocalDate.parse(it) },
        )
        return FinancialSummaryDto(
            totalIncome = summary.totalIncome,
            totalExpenses = summary.totalExpenses,
            totalPaymentsReceived = summary.totalPaymentsReceived,
            outstandingInvoices = summary.outstandingInvoices,
            overdueInvoices = summary.overdueInvoices,
            overdueAmount = summary.overdueAmount,
            recordCount = summary.recordCount,
        )
    }

    override suspend fun detectOverdueInvoices(): Int {
        return financialService.detectOverdueInvoices().size
    }

    override suspend fun createContract(request: ContractCreateDto): ContractDto {
        val doc = ContractDocument(
            clientId = request.clientId,
            projectId = request.projectId,
            counterparty = request.counterparty,
            type = ContractType.valueOf(request.type.name),
            startDate = LocalDate.parse(request.startDate),
            endDate = request.endDate?.let { LocalDate.parse(it) },
            rate = request.rate,
            rateUnit = RateUnit.valueOf(request.rateUnit.name),
            currency = request.currency,
            terms = request.terms,
        )
        return financialService.createContract(doc).toContractDto()
    }

    override suspend fun listContracts(clientId: String, activeOnly: Boolean): List<ContractDto> {
        val contracts = if (activeOnly) {
            financialService.listActiveContracts(clientId)
        } else {
            financialService.listContracts(clientId)
        }
        return contracts.map { it.toContractDto() }
    }

    override suspend fun updateContract(request: ContractUpdateDto): ContractDto? {
        return financialService.updateContract(
            request.id,
            ContractUpdateRequest(
                rate = request.rate,
                rateUnit = request.rateUnit?.let { RateUnit.valueOf(it.name) },
                endDate = request.endDate?.let { LocalDate.parse(it) },
                status = request.status?.let { ContractStatus.valueOf(it.name) },
                terms = request.terms,
            ),
        )?.toContractDto()
    }

    override suspend fun deleteContract(id: String): Boolean {
        return financialService.deleteContract(id)
    }

    private fun FinancialDocument.toDto() = FinancialRecordDto(
        id = id.toHexString(),
        clientId = clientId,
        projectId = projectId,
        type = FinancialTypeDto.valueOf(type.name),
        amount = amount,
        currency = currency,
        amountCzk = amountCzk,
        vatRate = vatRate,
        vatAmount = vatAmount,
        invoiceNumber = invoiceNumber,
        variableSymbol = variableSymbol,
        counterpartyName = counterpartyName,
        counterpartyIco = counterpartyIco,
        counterpartyAccount = counterpartyAccount,
        issueDate = issueDate?.toString(),
        dueDate = dueDate?.toString(),
        paymentDate = paymentDate?.toString(),
        status = FinancialStatusDto.valueOf(status.name),
        matchedDocumentId = matchedDocumentId?.toHexString(),
        sourceUrn = sourceUrn,
        description = description,
        createdAt = createdAt.toString(),
    )

    private fun ContractDocument.toContractDto() = ContractDto(
        id = id.toHexString(),
        clientId = clientId,
        projectId = projectId,
        counterparty = counterparty,
        type = ContractTypeDto.valueOf(type.name),
        startDate = startDate.toString(),
        endDate = endDate?.toString(),
        rate = rate,
        rateUnit = RateUnitDto.valueOf(rateUnit.name),
        currency = currency,
        terms = terms,
        status = ContractStatusDto.valueOf(status.name),
    )
}
