package com.jervis.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jervis.di.JervisRepository
import com.jervis.dto.finance.ContractCreateDto
import com.jervis.dto.finance.ContractDto
import com.jervis.dto.finance.ContractStatusDto
import com.jervis.dto.finance.ContractTypeDto
import com.jervis.dto.finance.ContractUpdateDto
import com.jervis.dto.finance.FinancialRecordCreateDto
import com.jervis.dto.finance.FinancialRecordDto
import com.jervis.dto.finance.FinancialStatusDto
import com.jervis.dto.finance.FinancialSummaryDto
import com.jervis.dto.finance.FinancialTypeDto
import com.jervis.dto.finance.RateUnitDto
import com.jervis.ui.LocalRpcGeneration
import com.jervis.ui.design.*
import kotlinx.coroutines.launch

private enum class FinanceCategory(
    val title: String,
    val icon: ImageVector,
    val description: String,
) {
    SUMMARY("Souhrn", Icons.Default.AccountBalance, "Finanční přehled klienta."),
    RECORDS("Záznamy", Icons.Default.Description, "Faktury, platby, výdaje."),
    CONTRACTS("Smlouvy", Icons.Default.AttachMoney, "Správa smluv a sazeb."),
}

@Composable
fun FinanceScreen(
    repository: JervisRepository,
    selectedClientId: String?,
    onBack: () -> Unit,
) {
    val categories = remember { FinanceCategory.entries.toList() }
    var selectedIndex by remember { mutableStateOf(0) }

    JAdaptiveSidebarLayout(
        categories = categories,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        onBack = onBack,
        title = "Finance",
        categoryIcon = { Icon(it.icon, contentDescription = it.title) },
        categoryTitle = { it.title },
        categoryDescription = { it.description },
        content = { category ->
            when (category) {
                FinanceCategory.SUMMARY -> SummarySection(repository, selectedClientId)
                FinanceCategory.RECORDS -> RecordsSection(repository, selectedClientId)
                FinanceCategory.CONTRACTS -> ContractsSection(repository, selectedClientId)
            }
        },
    )
}

// ── Summary Section ──

@Composable
private fun SummarySection(repository: JervisRepository, clientId: String?) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var summary by remember { mutableStateOf<FinancialSummaryDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration, clientId) {
        if (clientId.isNullOrBlank()) return@LaunchedEffect
        isLoading = true
        try {
            summary = repository.finance.getFinancialSummary(clientId)
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    Box {
        if (clientId.isNullOrBlank()) {
            JEmptyState("Vyberte klienta pro zobrazení finančního souhrnu.")
        } else if (isLoading) {
            JCenteredLoading()
        } else if (summary == null) {
            JEmptyState("Žádná finanční data.")
        } else {
            val s = summary!!
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                JSection(title = "Příjmy a výdaje") {
                    SummaryRow("Celkové příjmy", formatCzk(s.totalIncome))
                    SummaryRow("Celkové výdaje", formatCzk(s.totalExpenses))
                    SummaryRow("Přijaté platby", formatCzk(s.totalPaymentsReceived))
                }
                JSection(title = "Stav faktur") {
                    SummaryRow("Neuhrazené faktury", "${s.outstandingInvoices}")
                    SummaryRow("Po splatnosti", "${s.overdueInvoices}", isWarning = s.overdueInvoices > 0)
                    if (s.overdueInvoices > 0) {
                        SummaryRow("Dlužná částka", formatCzk(s.overdueAmount), isWarning = true)
                    }
                }
                JSection(title = "Celkem") {
                    SummaryRow("Počet záznamů", "${s.recordCount}")
                }

                // Detect overdue button
                JActionBar {
                    JPrimaryButton(
                        text = "Detekovat po splatnosti",
                        onClick = {
                            scope.launch {
                                try {
                                    val count = repository.finance.detectOverdueInvoices()
                                    snackbarHostState.showSnackbar("Nalezeno $count faktur po splatnosti.")
                                    // Reload summary
                                    summary = repository.finance.getFinancialSummary(clientId)
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                }
                            }
                        },
                    )
                }
            }
        }
        JSnackbarHost(snackbarHostState)
    }
}

@Composable
private fun SummaryRow(label: String, value: String, isWarning: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(JervisSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isWarning) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(label)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatCzk(amount: Double): String {
    val formatted = String.format("%,.0f", amount).replace(',', ' ')
    return "$formatted CZK"
}

// ── Records Section ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordsSection(repository: JervisRepository, clientId: String?) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var records by remember { mutableStateOf<List<FinancialRecordDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var filterStatus by remember { mutableStateOf<FinancialStatusDto?>(null) }
    var filterType by remember { mutableStateOf<FinancialTypeDto?>(null) }

    suspend fun loadRecords() {
        if (clientId.isNullOrBlank()) return
        isLoading = true
        try {
            records = repository.finance.listFinancialRecords(
                clientId,
                status = filterStatus?.name,
                type = filterType?.name,
            )
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration, clientId, filterStatus, filterType) { loadRecords() }

    Box {
        if (clientId.isNullOrBlank()) {
            JEmptyState("Vyberte klienta.")
        } else {
            Column {
                // Filters
                JActionBar {
                    // Status filter
                    var statusExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = statusExpanded,
                        onExpandedChange = { statusExpanded = it },
                        modifier = Modifier.width(180.dp),
                    ) {
                        OutlinedTextField(
                            value = filterStatus?.let { statusLabel(it) } ?: "Všechny stavy",
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                            DropdownMenuItem(text = { Text("Všechny stavy") }, onClick = { filterStatus = null; statusExpanded = false })
                            FinancialStatusDto.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(statusLabel(s)) }, onClick = { filterStatus = s; statusExpanded = false })
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Type filter
                    var typeExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = typeExpanded,
                        onExpandedChange = { typeExpanded = it },
                        modifier = Modifier.width(180.dp),
                    ) {
                        OutlinedTextField(
                            value = filterType?.let { typeLabel(it) } ?: "Všechny typy",
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                            DropdownMenuItem(text = { Text("Všechny typy") }, onClick = { filterType = null; typeExpanded = false })
                            FinancialTypeDto.entries.forEach { t ->
                                DropdownMenuItem(text = { Text(typeLabel(t)) }, onClick = { filterType = t; typeExpanded = false })
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    JPrimaryButton(
                        text = "Nový záznam",
                        icon = Icons.Default.Add,
                        onClick = { showCreateDialog = true },
                    )
                }

                if (isLoading) {
                    JCenteredLoading()
                } else if (records.isEmpty()) {
                    JEmptyState("Žádné finanční záznamy.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(records, key = { it.id }) { record ->
                            RecordCard(record, onStatusChange = { newStatus ->
                                scope.launch {
                                    try {
                                        repository.finance.updateFinancialStatus(record.id, newStatus.name)
                                        loadRecords()
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                    }
                                }
                            }, onDelete = {
                                scope.launch {
                                    try {
                                        repository.finance.deleteFinancialRecord(record.id)
                                        loadRecords()
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }

        if (showCreateDialog && !clientId.isNullOrBlank()) {
            CreateRecordDialog(
                clientId = clientId,
                onDismiss = { showCreateDialog = false },
                onSave = { dto ->
                    scope.launch {
                        try {
                            repository.finance.createFinancialRecord(dto)
                            showCreateDialog = false
                            loadRecords()
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Chyba: ${e.message}")
                        }
                    }
                },
            )
        }

        JSnackbarHost(snackbarHostState)
    }
}

@Composable
private fun RecordCard(
    record: FinancialRecordDto,
    onStatusChange: (FinancialStatusDto) -> Unit,
    onDelete: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable { showActions = !showActions },
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(typeLabel(record.type), style = MaterialTheme.typography.titleSmall)
                    record.counterpartyName?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatCzk(record.amountCzk),
                        style = MaterialTheme.typography.titleMedium,
                        color = when (record.type) {
                            FinancialTypeDto.PAYMENT, FinancialTypeDto.INVOICE_IN -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        statusLabel(record.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (record.status) {
                            FinancialStatusDto.OVERDUE -> MaterialTheme.colorScheme.error
                            FinancialStatusDto.PAID, FinancialStatusDto.MATCHED -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            // Detail row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                record.invoiceNumber?.let { Text("Faktura: $it", style = MaterialTheme.typography.bodySmall) }
                record.variableSymbol?.let { Text("VS: $it", style = MaterialTheme.typography.bodySmall) }
                record.dueDate?.let { Text("Splatnost: $it", style = MaterialTheme.typography.bodySmall) }
            }

            if (showActions) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (record.status == FinancialStatusDto.NEW) {
                        JPrimaryButton(text = "Zaplaceno", onClick = { onStatusChange(FinancialStatusDto.PAID) })
                        JPrimaryButton(text = "Zrušit", onClick = { onStatusChange(FinancialStatusDto.CANCELLED) })
                    }
                    if (record.status == FinancialStatusDto.OVERDUE) {
                        JPrimaryButton(text = "Zaplaceno", onClick = { onStatusChange(FinancialStatusDto.PAID) })
                    }
                    JPrimaryButton(text = "Smazat", onClick = onDelete)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateRecordDialog(
    clientId: String,
    onDismiss: () -> Unit,
    onSave: (FinancialRecordCreateDto) -> Unit,
) {
    var type by remember { mutableStateOf(FinancialTypeDto.INVOICE_OUT) }
    var amount by remember { mutableStateOf("") }
    var invoiceNumber by remember { mutableStateOf("") }
    var variableSymbol by remember { mutableStateOf("") }
    var counterpartyName by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var typeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nový finanční záznam") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Type picker
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = typeLabel(type),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Typ") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        FinancialTypeDto.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(typeLabel(t)) }, onClick = { type = t; typeExpanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Částka (CZK)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = counterpartyName,
                    onValueChange = { counterpartyName = it },
                    label = { Text("Protistrana") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = invoiceNumber,
                    onValueChange = { invoiceNumber = it },
                    label = { Text("Číslo faktury") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = variableSymbol,
                    onValueChange = { variableSymbol = it },
                    label = { Text("Variabilní symbol") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = dueDate,
                    onValueChange = { dueDate = it },
                    label = { Text("Splatnost (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Popis") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull() ?: return@TextButton
                    onSave(
                        FinancialRecordCreateDto(
                            clientId = clientId,
                            type = type,
                            amount = parsedAmount,
                            amountCzk = parsedAmount,
                            invoiceNumber = invoiceNumber.ifBlank { null },
                            variableSymbol = variableSymbol.ifBlank { null },
                            counterpartyName = counterpartyName.ifBlank { null },
                            dueDate = dueDate.ifBlank { null },
                            description = description,
                        ),
                    )
                },
            ) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

// ── Contracts Section ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContractsSection(repository: JervisRepository, clientId: String?) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var contracts by remember { mutableStateOf<List<ContractDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingContract by remember { mutableStateOf<ContractDto?>(null) }

    suspend fun loadContracts() {
        if (clientId.isNullOrBlank()) return
        isLoading = true
        try {
            contracts = repository.finance.listContracts(clientId)
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration, clientId) { loadContracts() }

    Box {
        if (clientId.isNullOrBlank()) {
            JEmptyState("Vyberte klienta.")
        } else {
            Column {
                JActionBar {
                    Spacer(modifier = Modifier.weight(1f))
                    JPrimaryButton(
                        text = "Nová smlouva",
                        icon = Icons.Default.Add,
                        onClick = { showCreateDialog = true },
                    )
                }

                if (isLoading) {
                    JCenteredLoading()
                } else if (contracts.isEmpty()) {
                    JEmptyState("Žádné smlouvy.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(contracts, key = { it.id }) { contract ->
                            ContractCard(contract, onEdit = { editingContract = contract }, onTerminate = {
                                scope.launch {
                                    try {
                                        repository.finance.updateContract(ContractUpdateDto(
                                            id = contract.id,
                                            status = ContractStatusDto.TERMINATED,
                                        ))
                                        loadContracts()
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }

        if (showCreateDialog && !clientId.isNullOrBlank()) {
            CreateContractDialog(
                clientId = clientId,
                onDismiss = { showCreateDialog = false },
                onSave = { dto ->
                    scope.launch {
                        try {
                            repository.finance.createContract(dto)
                            showCreateDialog = false
                            loadContracts()
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Chyba: ${e.message}")
                        }
                    }
                },
            )
        }

        if (editingContract != null) {
            EditContractDialog(
                contract = editingContract!!,
                onDismiss = { editingContract = null },
                onSave = { dto ->
                    scope.launch {
                        try {
                            repository.finance.updateContract(dto)
                            editingContract = null
                            loadContracts()
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Chyba: ${e.message}")
                        }
                    }
                },
            )
        }

        JSnackbarHost(snackbarHostState)
    }
}

@Composable
private fun ContractCard(
    contract: ContractDto,
    onEdit: () -> Unit,
    onTerminate: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable { showActions = !showActions },
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(contract.counterparty, style = MaterialTheme.typography.titleSmall)
                    Text(contractTypeLabel(contract.type), style = MaterialTheme.typography.bodyMedium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${formatRate(contract.rate)} ${contract.currency}/${rateUnitLabel(contract.rateUnit)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        contractStatusLabel(contract.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (contract.status) {
                            ContractStatusDto.ACTIVE -> MaterialTheme.colorScheme.primary
                            ContractStatusDto.TERMINATED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Od: ${contract.startDate}", style = MaterialTheme.typography.bodySmall)
                contract.endDate?.let { Text("Do: $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (contract.terms.isNotBlank()) {
                Text(contract.terms, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            if (showActions && contract.status == ContractStatusDto.ACTIVE) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JPrimaryButton(text = "Upravit", onClick = onEdit)
                    JPrimaryButton(text = "Ukončit", onClick = onTerminate)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateContractDialog(
    clientId: String,
    onDismiss: () -> Unit,
    onSave: (ContractCreateDto) -> Unit,
) {
    var counterparty by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ContractTypeDto.FREELANCE) }
    var rate by remember { mutableStateOf("") }
    var rateUnit by remember { mutableStateOf(RateUnitDto.DAY) }
    var currency by remember { mutableStateOf("CZK") }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var terms by remember { mutableStateOf("") }

    var typeExpanded by remember { mutableStateOf(false) }
    var unitExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nová smlouva") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = counterparty,
                    onValueChange = { counterparty = it },
                    label = { Text("Protistrana") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = contractTypeLabel(type),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Typ") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        ContractTypeDto.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(contractTypeLabel(t)) }, onClick = { type = t; typeExpanded = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rate,
                        onValueChange = { rate = it },
                        label = { Text("Sazba") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    ExposedDropdownMenuBox(
                        expanded = unitExpanded,
                        onExpandedChange = { unitExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = rateUnitLabel(rateUnit),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Jednotka") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                            RateUnitDto.entries.forEach { u ->
                                DropdownMenuItem(text = { Text(rateUnitLabel(u)) }, onClick = { rateUnit = u; unitExpanded = false })
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it },
                    label = { Text("Měna") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("Od (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = { Text("Do (YYYY-MM-DD, nepovinné)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = terms,
                    onValueChange = { terms = it },
                    label = { Text("Podmínky") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedRate = rate.toDoubleOrNull() ?: return@TextButton
                    if (counterparty.isBlank() || startDate.isBlank()) return@TextButton
                    onSave(
                        ContractCreateDto(
                            clientId = clientId,
                            counterparty = counterparty,
                            type = type,
                            rate = parsedRate,
                            rateUnit = rateUnit,
                            currency = currency,
                            startDate = startDate,
                            endDate = endDate.ifBlank { null },
                            terms = terms,
                        ),
                    )
                },
            ) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditContractDialog(
    contract: ContractDto,
    onDismiss: () -> Unit,
    onSave: (ContractUpdateDto) -> Unit,
) {
    var rate by remember { mutableStateOf(contract.rate.toString()) }
    var rateUnit by remember { mutableStateOf(contract.rateUnit) }
    var endDate by remember { mutableStateOf(contract.endDate ?: "") }
    var terms by remember { mutableStateOf(contract.terms) }
    var unitExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upravit smlouvu — ${contract.counterparty}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rate,
                        onValueChange = { rate = it },
                        label = { Text("Sazba") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    ExposedDropdownMenuBox(
                        expanded = unitExpanded,
                        onExpandedChange = { unitExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = rateUnitLabel(rateUnit),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Jednotka") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                            RateUnitDto.entries.forEach { u ->
                                DropdownMenuItem(text = { Text(rateUnitLabel(u)) }, onClick = { rateUnit = u; unitExpanded = false })
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = { Text("Konec (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = terms,
                    onValueChange = { terms = it },
                    label = { Text("Podmínky") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ContractUpdateDto(
                            id = contract.id,
                            rate = rate.toDoubleOrNull(),
                            rateUnit = rateUnit,
                            endDate = endDate.ifBlank { null },
                            terms = terms,
                        ),
                    )
                },
            ) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

// ── Label helpers ──

private fun typeLabel(type: FinancialTypeDto): String = when (type) {
    FinancialTypeDto.INVOICE_IN -> "Přijatá faktura"
    FinancialTypeDto.INVOICE_OUT -> "Vydaná faktura"
    FinancialTypeDto.PAYMENT -> "Platba"
    FinancialTypeDto.EXPENSE -> "Výdaj"
    FinancialTypeDto.RECEIPT -> "Příjem"
}

private fun statusLabel(status: FinancialStatusDto): String = when (status) {
    FinancialStatusDto.NEW -> "Nový"
    FinancialStatusDto.MATCHED -> "Spárováno"
    FinancialStatusDto.PAID -> "Zaplaceno"
    FinancialStatusDto.OVERDUE -> "Po splatnosti"
    FinancialStatusDto.CANCELLED -> "Zrušeno"
}

private fun contractTypeLabel(type: ContractTypeDto): String = when (type) {
    ContractTypeDto.EMPLOYMENT -> "Zaměstnanecký"
    ContractTypeDto.FREELANCE -> "OSVČ / Freelance"
    ContractTypeDto.SERVICE -> "Služba"
}

private fun rateUnitLabel(unit: RateUnitDto): String = when (unit) {
    RateUnitDto.HOUR -> "hodina"
    RateUnitDto.DAY -> "den"
    RateUnitDto.MONTH -> "měsíc"
}

private fun contractStatusLabel(status: ContractStatusDto): String = when (status) {
    ContractStatusDto.ACTIVE -> "Aktivní"
    ContractStatusDto.EXPIRED -> "Vypršela"
    ContractStatusDto.TERMINATED -> "Ukončena"
}

private fun formatRate(rate: Double): String {
    return if (rate == rate.toLong().toDouble()) {
        String.format("%,.0f", rate).replace(',', ' ')
    } else {
        String.format("%,.2f", rate).replace(',', ' ')
    }
}
