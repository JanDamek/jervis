package com.jervis.finance

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.time.LocalDate

/**
 * Financial record — invoices, payments, expenses.
 *
 * Tracks all financial transactions with auto-matching between
 * invoices and payments via variable symbol or amount+counterparty.
 */
@Document(collection = "financial_records")
@CompoundIndex(def = "{'clientId': 1, 'status': 1}")
@CompoundIndex(def = "{'variableSymbol': 1}")
@CompoundIndex(def = "{'dueDate': 1, 'status': 1}")
data class FinancialDocument(
    @Id
    val id: ObjectId = ObjectId(),
    val clientId: String,
    val projectId: String? = null,
    val type: FinancialType,
    val amount: Double,
    val currency: String = "CZK",
    val amountCzk: Double,
    val vatRate: Double? = null,
    val vatAmount: Double? = null,
    val invoiceNumber: String? = null,
    val variableSymbol: String? = null,
    val counterpartyName: String? = null,
    val counterpartyIco: String? = null,
    val counterpartyAccount: String? = null,
    val issueDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val paymentDate: LocalDate? = null,
    val status: FinancialStatus = FinancialStatus.NEW,
    val matchedDocumentId: ObjectId? = null,
    val sourceUrn: String,
    val description: String = "",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class FinancialType {
    INVOICE_IN,
    INVOICE_OUT,
    PAYMENT,
    EXPENSE,
    RECEIPT,
}

enum class FinancialStatus {
    NEW,
    MATCHED,
    PAID,
    OVERDUE,
    CANCELLED,
}
