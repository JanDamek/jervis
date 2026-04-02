package com.jervis.rpc.internal

import com.jervis.finance.FinancialService
import com.jervis.finance.FinancialDocument
import com.jervis.finance.FinancialType
import com.jervis.finance.FinancialStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.time.LocalDate

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Internal REST API for financial operations — used by Python orchestrator.
 *
 * POST /internal/finance/record   — create a financial record (from invoice extraction)
 * GET  /internal/finance/summary  — get client financial summary
 * POST /internal/finance/overdue  — detect and update overdue invoices
 */
fun Routing.installInternalFinanceApi(
    financialService: FinancialService,
) {
    post("/internal/finance/record") {
        try {
            val body = call.receive<InternalFinancialRecordRequest>()
            val doc = FinancialDocument(
                clientId = body.clientId,
                projectId = body.projectId,
                type = FinancialType.valueOf(body.type),
                amount = body.amount,
                currency = body.currency ?: "CZK",
                amountCzk = body.amountCzk ?: body.amount,
                vatRate = body.vatRate,
                vatAmount = body.vatAmount,
                invoiceNumber = body.invoiceNumber,
                variableSymbol = body.variableSymbol,
                counterpartyName = body.counterpartyName,
                counterpartyIco = body.counterpartyIco,
                counterpartyAccount = body.counterpartyAccount,
                issueDate = body.issueDate?.let { LocalDate.parse(it) },
                dueDate = body.dueDate?.let { LocalDate.parse(it) },
                paymentDate = body.paymentDate?.let { LocalDate.parse(it) },
                sourceUrn = body.sourceUrn ?: "orchestrator",
                description = body.description ?: "",
            )
            val saved = financialService.createFinancialRecord(doc)
            call.respondText(
                """{"status":"ok","id":"${saved.id.toHexString()}","matched":${saved.status == FinancialStatus.MATCHED}}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "INTERNAL_FINANCE_RECORD_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/internal/finance/summary") {
        try {
            val clientId = call.request.queryParameters["client_id"]
                ?: return@get call.respondText("""{"error":"client_id required"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            val fromDate = call.request.queryParameters["from"]?.let { LocalDate.parse(it) }
            val toDate = call.request.queryParameters["to"]?.let { LocalDate.parse(it) }

            val summary = financialService.getClientSummary(clientId, fromDate, toDate)
            call.respondText(
                json.encodeToString(InternalFinancialSummaryResponse(
                    totalIncome = summary.totalIncome,
                    totalExpenses = summary.totalExpenses,
                    totalPaymentsReceived = summary.totalPaymentsReceived,
                    outstandingInvoices = summary.outstandingInvoices,
                    overdueInvoices = summary.overdueInvoices,
                    overdueAmount = summary.overdueAmount,
                    recordCount = summary.recordCount,
                )),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "INTERNAL_FINANCE_SUMMARY_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/internal/finance/records") {
        try {
            val clientId = call.request.queryParameters["client_id"]
                ?: return@get call.respondText("""{"error":"client_id required"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            val status = call.request.queryParameters["status"]?.let { FinancialStatus.valueOf(it) }
            val type = call.request.queryParameters["type"]?.let { FinancialType.valueOf(it) }

            val records = when {
                status != null -> financialService.listByClientAndStatus(clientId, status)
                type != null -> financialService.listByClientAndType(clientId, type)
                else -> financialService.listByClient(clientId)
            }
            val dtos = records.map { doc ->
                mapOf(
                    "id" to doc.id.toHexString(),
                    "type" to doc.type.name,
                    "amount" to doc.amount,
                    "currency" to doc.currency,
                    "amountCzk" to doc.amountCzk,
                    "invoiceNumber" to doc.invoiceNumber,
                    "variableSymbol" to doc.variableSymbol,
                    "counterpartyName" to doc.counterpartyName,
                    "status" to doc.status.name,
                    "issueDate" to doc.issueDate?.toString(),
                    "dueDate" to doc.dueDate?.toString(),
                    "description" to doc.description,
                )
            }
            call.respondText(
                json.encodeToString(dtos),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "INTERNAL_FINANCE_RECORDS_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/internal/finance/contracts") {
        try {
            val clientId = call.request.queryParameters["client_id"]
            val activeOnly = call.request.queryParameters["active_only"]?.toBoolean() ?: false

            val contracts = if (activeOnly) {
                financialService.listActiveContracts(clientId)
            } else if (clientId != null) {
                financialService.listContracts(clientId)
            } else {
                financialService.listActiveContracts(null)
            }
            val dtos = contracts.map { doc ->
                mapOf(
                    "id" to doc.id.toHexString(),
                    "clientId" to doc.clientId,
                    "counterparty" to doc.counterparty,
                    "type" to doc.type.name,
                    "rate" to doc.rate,
                    "rateUnit" to doc.rateUnit.name,
                    "currency" to doc.currency,
                    "startDate" to doc.startDate.toString(),
                    "endDate" to doc.endDate?.toString(),
                    "status" to doc.status.name,
                )
            }
            call.respondText(
                json.encodeToString(dtos),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "INTERNAL_FINANCE_CONTRACTS_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/finance/overdue") {
        try {
            val overdueCount = financialService.detectOverdueInvoices().size
            call.respondText(
                """{"status":"ok","overdueCount":$overdueCount}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "INTERNAL_FINANCE_OVERDUE_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
data class InternalFinancialRecordRequest(
    val clientId: String,
    val projectId: String? = null,
    val type: String,
    val amount: Double,
    val currency: String? = null,
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
    val sourceUrn: String? = null,
    val description: String? = null,
)

@Serializable
data class InternalFinancialSummaryResponse(
    val totalIncome: Double,
    val totalExpenses: Double,
    val totalPaymentsReceived: Double,
    val outstandingInvoices: Int,
    val overdueInvoices: Int,
    val overdueAmount: Double,
    val recordCount: Int,
)
