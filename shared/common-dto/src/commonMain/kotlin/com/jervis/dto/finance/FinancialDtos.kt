package com.jervis.dto.finance

import kotlinx.serialization.Serializable

@Serializable
enum class FinancialTypeDto {
    INVOICE_IN,
    INVOICE_OUT,
    PAYMENT,
    EXPENSE,
    RECEIPT,
}

@Serializable
enum class FinancialStatusDto {
    NEW,
    MATCHED,
    PAID,
    OVERDUE,
    CANCELLED,
}

@Serializable
enum class ContractTypeDto {
    EMPLOYMENT,
    FREELANCE,
    SERVICE,
}

@Serializable
enum class RateUnitDto {
    HOUR,
    DAY,
    MONTH,
}

@Serializable
enum class ContractStatusDto {
    ACTIVE,
    EXPIRED,
    TERMINATED,
}

@Serializable
data class FinancialRecordDto(
    val id: String,
    val clientId: String,
    val projectId: String? = null,
    val type: FinancialTypeDto,
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
    val issueDate: String? = null,
    val dueDate: String? = null,
    val paymentDate: String? = null,
    val status: FinancialStatusDto,
    val matchedDocumentId: String? = null,
    val sourceUrn: String,
    val description: String = "",
    val createdAt: String = "",
)

@Serializable
data class FinancialRecordCreateDto(
    val clientId: String,
    val projectId: String? = null,
    val type: FinancialTypeDto,
    val amount: Double,
    val currency: String = "CZK",
    val amountCzk: Double? = null,
    val vatRate: Double? = null,
    val vatAmount: Double? = null,
    val invoiceNumber: String? = null,
    val variableSymbol: String? = null,
    val counterpartyName: String? = null,
    val counterpartyIco: String? = null,
    val counterpartyAccount: String? = null,
    val issueDate: String? = null,
    val dueDate: String? = null,
    val paymentDate: String? = null,
    val sourceUrn: String = "manual",
    val description: String = "",
)

@Serializable
data class ContractDto(
    val id: String,
    val clientId: String,
    val projectId: String? = null,
    val counterparty: String,
    val type: ContractTypeDto,
    val startDate: String,
    val endDate: String? = null,
    val rate: Double,
    val rateUnit: RateUnitDto,
    val currency: String = "CZK",
    val terms: String = "",
    val status: ContractStatusDto,
)

@Serializable
data class ContractCreateDto(
    val clientId: String,
    val projectId: String? = null,
    val counterparty: String,
    val type: ContractTypeDto,
    val startDate: String,
    val endDate: String? = null,
    val rate: Double,
    val rateUnit: RateUnitDto,
    val currency: String = "CZK",
    val terms: String = "",
)

@Serializable
data class ContractUpdateDto(
    val id: String,
    val rate: Double? = null,
    val rateUnit: RateUnitDto? = null,
    val endDate: String? = null,
    val status: ContractStatusDto? = null,
    val terms: String? = null,
)

@Serializable
data class FinancialSummaryDto(
    val totalIncome: Double,
    val totalExpenses: Double,
    val totalPaymentsReceived: Double,
    val outstandingInvoices: Int,
    val overdueInvoices: Int,
    val overdueAmount: Double,
    val recordCount: Int,
)
