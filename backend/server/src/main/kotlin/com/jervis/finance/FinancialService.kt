package com.jervis.finance

import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Financial management service — CRUD, auto-matching, overdue detection, reports.
 */
@Service
class FinancialService(
    private val financialRepository: FinancialRepository,
    private val contractRepository: ContractRepository,
) {

    // ── Financial Documents ──

    suspend fun createFinancialRecord(doc: FinancialDocument): FinancialDocument {
        val saved = financialRepository.save(doc)
        logger.info { "Created financial record: type=${doc.type}, amount=${doc.amountCzk} CZK, client=${doc.clientId}" }

        // Auto-match after creation
        if (doc.type == FinancialType.PAYMENT || doc.type == FinancialType.INVOICE_IN) {
            tryAutoMatch(saved)
        }

        return saved
    }

    suspend fun getFinancialRecord(id: String): FinancialDocument? {
        return financialRepository.findById(ObjectId(id))
    }

    suspend fun listByClient(clientId: String): List<FinancialDocument> {
        return financialRepository.findByClientId(clientId).toList()
    }

    suspend fun listByClientAndStatus(clientId: String, status: FinancialStatus): List<FinancialDocument> {
        return financialRepository.findByClientIdAndStatus(clientId, status).toList()
    }

    suspend fun listByClientAndType(clientId: String, type: FinancialType): List<FinancialDocument> {
        return financialRepository.findByClientIdAndType(clientId, type).toList()
    }

    suspend fun updateStatus(id: String, status: FinancialStatus): FinancialDocument? {
        val doc = financialRepository.findById(ObjectId(id)) ?: return null
        val updated = doc.copy(status = status, updatedAt = Instant.now())
        return financialRepository.save(updated)
    }

    suspend fun deleteFinancialRecord(id: String): Boolean {
        val oid = ObjectId(id)
        val exists = financialRepository.findById(oid) != null
        if (exists) financialRepository.deleteById(oid)
        return exists
    }

    // ── Auto-matching ──

    /**
     * Try to match a payment to an invoice (or vice versa) by:
     * 1. Variable symbol match
     * 2. Amount + counterparty match
     */
    private suspend fun tryAutoMatch(doc: FinancialDocument) {
        // Match by variable symbol
        if (!doc.variableSymbol.isNullOrBlank()) {
            val candidates = financialRepository.findByVariableSymbol(doc.variableSymbol).toList()
            val match = candidates.firstOrNull { it.id != doc.id && isMatchCandidate(doc, it) }
            if (match != null) {
                markMatched(doc, match)
                return
            }
        }

        // Match by amount + counterparty
        if (!doc.counterpartyName.isNullOrBlank()) {
            val targetType = when (doc.type) {
                FinancialType.PAYMENT -> FinancialType.INVOICE_IN
                FinancialType.INVOICE_IN -> FinancialType.PAYMENT
                else -> return
            }
            val candidates = financialRepository.findByClientIdAndType(doc.clientId, targetType).toList()
            val match = candidates.firstOrNull { candidate ->
                candidate.status == FinancialStatus.NEW &&
                candidate.amountCzk == doc.amountCzk &&
                candidate.counterpartyName?.lowercase() == doc.counterpartyName.lowercase()
            }
            if (match != null) {
                markMatched(doc, match)
            }
        }
    }

    private fun isMatchCandidate(doc: FinancialDocument, candidate: FinancialDocument): Boolean {
        val typePair = setOf(doc.type, candidate.type)
        return typePair == setOf(FinancialType.INVOICE_IN, FinancialType.PAYMENT) &&
            candidate.status == FinancialStatus.NEW
    }

    private suspend fun markMatched(a: FinancialDocument, b: FinancialDocument) {
        financialRepository.save(a.copy(
            status = FinancialStatus.MATCHED,
            matchedDocumentId = b.id,
            updatedAt = Instant.now(),
        ))
        financialRepository.save(b.copy(
            status = FinancialStatus.MATCHED,
            matchedDocumentId = a.id,
            updatedAt = Instant.now(),
        ))
        logger.info { "Auto-matched: ${a.type} ${a.id} <-> ${b.type} ${b.id} (VS=${a.variableSymbol}, amount=${a.amountCzk})" }
    }

    // ── Overdue Detection ──

    suspend fun detectOverdueInvoices(): List<FinancialDocument> {
        val today = LocalDate.now()
        val overdue = financialRepository.findByStatusAndDueDateBefore(FinancialStatus.NEW, today).toList()
        for (doc in overdue) {
            financialRepository.save(doc.copy(status = FinancialStatus.OVERDUE, updatedAt = Instant.now()))
            logger.info { "Invoice overdue: ${doc.invoiceNumber ?: doc.id}, due=${doc.dueDate}, amount=${doc.amountCzk} CZK" }
        }
        return overdue
    }

    // ── Reports ──

    suspend fun getClientSummary(clientId: String, fromDate: LocalDate? = null, toDate: LocalDate? = null): FinancialSummary {
        val all = financialRepository.findByClientId(clientId).toList()
        val filtered = if (fromDate != null || toDate != null) {
            all.filter { doc ->
                val date = doc.issueDate ?: doc.createdAt.let { LocalDate.ofInstant(it, java.time.ZoneId.systemDefault()) }
                (fromDate == null || !date.isBefore(fromDate)) && (toDate == null || !date.isAfter(toDate))
            }
        } else all

        val invoicesIn = filtered.filter { it.type == FinancialType.INVOICE_IN }
        val invoicesOut = filtered.filter { it.type == FinancialType.INVOICE_OUT }
        val payments = filtered.filter { it.type == FinancialType.PAYMENT }
        val expenses = filtered.filter { it.type == FinancialType.EXPENSE }

        return FinancialSummary(
            totalIncome = invoicesIn.sumOf { it.amountCzk },
            totalExpenses = invoicesOut.sumOf { it.amountCzk } + expenses.sumOf { it.amountCzk },
            totalPaymentsReceived = payments.filter { it.amountCzk > 0 }.sumOf { it.amountCzk },
            outstandingInvoices = invoicesIn.count { it.status == FinancialStatus.NEW || it.status == FinancialStatus.OVERDUE },
            overdueInvoices = invoicesIn.count { it.status == FinancialStatus.OVERDUE },
            overdueAmount = invoicesIn.filter { it.status == FinancialStatus.OVERDUE }.sumOf { it.amountCzk },
            recordCount = filtered.size,
        )
    }

    // ── Contracts ──

    suspend fun createContract(doc: ContractDocument): ContractDocument {
        val saved = contractRepository.save(doc)
        logger.info { "Created contract: client=${doc.clientId}, counterparty=${doc.counterparty}, rate=${doc.rate} ${doc.currency}/${doc.rateUnit}" }
        return saved
    }

    suspend fun listContracts(clientId: String): List<ContractDocument> {
        return contractRepository.findByClientId(clientId).toList()
    }

    suspend fun listActiveContracts(clientId: String? = null): List<ContractDocument> {
        return if (clientId != null) {
            contractRepository.findByClientIdAndStatus(clientId, ContractStatus.ACTIVE).toList()
        } else {
            contractRepository.findByStatus(ContractStatus.ACTIVE).toList()
        }
    }

    suspend fun updateContract(id: String, updates: ContractUpdateRequest): ContractDocument? {
        val doc = contractRepository.findById(ObjectId(id)) ?: return null
        val updated = doc.copy(
            rate = updates.rate ?: doc.rate,
            rateUnit = updates.rateUnit ?: doc.rateUnit,
            endDate = updates.endDate ?: doc.endDate,
            status = updates.status ?: doc.status,
            terms = updates.terms ?: doc.terms,
            updatedAt = Instant.now(),
        )
        return contractRepository.save(updated)
    }

    suspend fun deleteContract(id: String): Boolean {
        val oid = ObjectId(id)
        val exists = contractRepository.findById(oid) != null
        if (exists) contractRepository.deleteById(oid)
        return exists
    }
}

data class FinancialSummary(
    val totalIncome: Double,
    val totalExpenses: Double,
    val totalPaymentsReceived: Double,
    val outstandingInvoices: Int,
    val overdueInvoices: Int,
    val overdueAmount: Double,
    val recordCount: Int,
)

data class ContractUpdateRequest(
    val rate: Double? = null,
    val rateUnit: RateUnit? = null,
    val endDate: LocalDate? = null,
    val status: ContractStatus? = null,
    val terms: String? = null,
)
