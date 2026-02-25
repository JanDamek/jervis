package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.guidelines.ApprovalGuidelinesDto
import com.jervis.dto.guidelines.ApprovalRule
import com.jervis.dto.guidelines.CodingGuidelinesDto
import com.jervis.dto.guidelines.CommunicationGuidelinesDto
import com.jervis.dto.guidelines.GeneralGuidelinesDto
import com.jervis.dto.guidelines.GitGuidelinesDto
import com.jervis.dto.guidelines.GuidelinesDocumentDto
import com.jervis.dto.guidelines.GuidelinesScope
import com.jervis.dto.guidelines.GuidelinesUpdateRequest
import com.jervis.dto.guidelines.PatternRule
import com.jervis.dto.guidelines.PatternSeverity
import com.jervis.dto.guidelines.ReviewChecklistItem
import com.jervis.dto.guidelines.ReviewGuidelinesDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JCheckboxRow
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JSwitch
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

/**
 * Guidelines settings screen — hierarchical rules engine (GLOBAL → CLIENT → PROJECT).
 *
 * Three tabs for scope selection, forms for each guidelines category.
 */
@Composable
internal fun GuidelinesSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Scope selection
    var selectedScopeIndex by remember { mutableIntStateOf(0) }
    val scopeTabs = listOf("Globální", "Klient", "Projekt")

    // Client/project selection for CLIENT and PROJECT scopes
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }

    // Current guidelines state
    var guidelines by remember { mutableStateOf(GuidelinesDocumentDto()) }

    fun currentScope(): GuidelinesScope = when (selectedScopeIndex) {
        0 -> GuidelinesScope.GLOBAL
        1 -> GuidelinesScope.CLIENT
        else -> GuidelinesScope.PROJECT
    }

    fun loadGuidelines() {
        scope.launch {
            isLoading = true
            try {
                val s = currentScope()
                val clientId = when (s) {
                    GuidelinesScope.GLOBAL -> null
                    else -> selectedClient?.id
                }
                val projectId = when (s) {
                    GuidelinesScope.PROJECT -> selectedProject?.id
                    else -> null
                }
                guidelines = repository.guidelines.getGuidelines(
                    scope = s.name,
                    clientId = clientId,
                    projectId = projectId,
                )
                error = null
            } catch (e: Exception) {
                error = "Chyba načítání: ${e.message}"
            }
            isLoading = false
        }
    }

    fun saveGuidelines() {
        scope.launch {
            try {
                val s = currentScope()
                val updated = repository.guidelines.updateGuidelines(
                    GuidelinesUpdateRequest(
                        scope = s,
                        clientId = when (s) {
                            GuidelinesScope.GLOBAL -> null
                            else -> selectedClient?.id
                        },
                        projectId = when (s) {
                            GuidelinesScope.PROJECT -> selectedProject?.id
                            else -> null
                        },
                        coding = guidelines.coding,
                        git = guidelines.git,
                        review = guidelines.review,
                        communication = guidelines.communication,
                        approval = guidelines.approval,
                        general = guidelines.general,
                    ),
                )
                guidelines = updated
                snackbarHostState.showSnackbar("Pravidla uložena")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba: ${e.message}")
            }
        }
    }

    // Load clients on start
    LaunchedEffect(Unit) {
        try {
            clients = repository.clients.getAllClients()
        } catch (_: Exception) { }
        loadGuidelines()
    }

    // Load projects when client changes
    LaunchedEffect(selectedClient) {
        val cid = selectedClient?.id ?: return@LaunchedEffect
        try {
            projects = repository.projects.getProjectsByClientId(cid)
        } catch (_: Exception) {
            projects = emptyList()
        }
    }

    // Reload guidelines when scope or selection changes
    LaunchedEffect(selectedScopeIndex, selectedClient, selectedProject) {
        loadGuidelines()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Scope tabs
            TabRow(selectedTabIndex = selectedScopeIndex) {
                scopeTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedScopeIndex == index,
                        onClick = { selectedScopeIndex = index },
                        text = { Text(title) },
                    )
                }
            }

            // Client/Project selectors for non-global scopes
            if (selectedScopeIndex >= 1) {
                Spacer(Modifier.height(8.dp))
                JDropdown(
                    items = clients,
                    selectedItem = selectedClient,
                    onItemSelected = { selectedClient = it; selectedProject = null },
                    label = "Klient",
                    itemLabel = { it.name },
                    placeholder = "Vyberte klienta",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = JervisSpacing.outerPadding),
                )
            }
            if (selectedScopeIndex == 2 && selectedClient != null) {
                Spacer(Modifier.height(8.dp))
                JDropdown(
                    items = projects,
                    selectedItem = selectedProject,
                    onItemSelected = { selectedProject = it },
                    label = "Projekt",
                    itemLabel = { it.name },
                    placeholder = "Vyberte projekt",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = JervisSpacing.outerPadding),
                )
            }

            Spacer(Modifier.height(8.dp))

            // Content
            when {
                isLoading -> JCenteredLoading()
                error != null -> JErrorState(message = error!!, onRetry = { loadGuidelines() })
                selectedScopeIndex >= 1 && selectedClient == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Vyberte klienta", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                selectedScopeIndex == 2 && selectedProject == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Vyberte projekt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
                    ) {
                        ScopeIndicator(currentScope(), selectedClient?.name, selectedProject?.name)
                        CodingSection(guidelines.coding) { guidelines = guidelines.copy(coding = it) }
                        GitSection(guidelines.git) { guidelines = guidelines.copy(git = it) }
                        ReviewSection(guidelines.review) { guidelines = guidelines.copy(review = it) }
                        CommunicationSection(guidelines.communication) { guidelines = guidelines.copy(communication = it) }
                        ApprovalSection(guidelines.approval) { guidelines = guidelines.copy(approval = it) }
                        GeneralSection(guidelines.general) { guidelines = guidelines.copy(general = it) }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(JervisSpacing.outerPadding),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            JPrimaryButton(onClick = { saveGuidelines() }) {
                                Text("Uložit pravidla")
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }

        JSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }
}

@Composable
private fun ScopeIndicator(scope: GuidelinesScope, clientName: String?, projectName: String?) {
    val label = when (scope) {
        GuidelinesScope.GLOBAL -> "Globální pravidla (platí pro všechny klienty a projekty)"
        GuidelinesScope.CLIENT -> "Pravidla klienta: ${clientName ?: "?"} (přepisují globální)"
        GuidelinesScope.PROJECT -> "Pravidla projektu: ${projectName ?: "?"} (přepisují klientská i globální)"
    }
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = JervisSpacing.outerPadding),
    )
}

@Composable
private fun CodingSection(coding: CodingGuidelinesDto, onUpdate: (CodingGuidelinesDto) -> Unit) {
    JSection(title = "Coding pravidla") {
        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
            JTextField(
                value = coding.maxFileLines?.toString() ?: "",
                onValueChange = { onUpdate(coding.copy(maxFileLines = it.toIntOrNull())) },
                label = "Max. řádků na soubor",
                placeholder = "500",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = coding.maxFunctionLines?.toString() ?: "",
                onValueChange = { onUpdate(coding.copy(maxFunctionLines = it.toIntOrNull())) },
                label = "Max. řádků na funkci",
                placeholder = "100",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = coding.forbiddenPatterns.joinToString("\n") { it.pattern },
                onValueChange = { text ->
                    val patterns = text.lines().filter { it.isNotBlank() }.map {
                        PatternRule(pattern = it.trim(), severity = PatternSeverity.BLOCKER)
                    }
                    onUpdate(coding.copy(forbiddenPatterns = patterns))
                },
                label = "Zakázané patterny (regex, jeden na řádek)",
                placeholder = "hardcoded_password.*=\nTODO|FIXME|HACK",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = coding.requiredPatterns.joinToString("\n") { it.pattern },
                onValueChange = { text ->
                    val patterns = text.lines().filter { it.isNotBlank() }.map {
                        PatternRule(pattern = it.trim(), severity = PatternSeverity.WARNING)
                    }
                    onUpdate(coding.copy(requiredPatterns = patterns))
                },
                label = "Vyžadované patterny (regex, jeden na řádek)",
                placeholder = "Copyright.*\\d{4}",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GitSection(git: GitGuidelinesDto, onUpdate: (GitGuidelinesDto) -> Unit) {
    JSection(title = "Git pravidla") {
        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
            JTextField(
                value = git.commitMessageTemplate ?: "",
                onValueChange = { onUpdate(git.copy(commitMessageTemplate = it.ifBlank { null })) },
                label = "Šablona commit zprávy",
                placeholder = "feat|fix|refactor|docs|test|chore(scope): description",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = git.branchNameTemplate ?: "",
                onValueChange = { onUpdate(git.copy(branchNameTemplate = it.ifBlank { null })) },
                label = "Šablona názvu větve",
                placeholder = "task/{taskId}-{short-description}",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = git.commitMessageValidators.joinToString("\n"),
                onValueChange = { text ->
                    onUpdate(git.copy(commitMessageValidators = text.lines().filter { it.isNotBlank() }.map { it.trim() }))
                },
                label = "Validátory commit zpráv (regex, jeden na řádek)",
                placeholder = "^(feat|fix|refactor|docs|test|chore)\\(.*\\):.*",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = git.protectedBranches.joinToString(", "),
                onValueChange = { text ->
                    onUpdate(git.copy(protectedBranches = text.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                },
                label = "Chráněné větve (oddělené čárkou)",
                placeholder = "main, master, release/*",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JCheckboxRow(
                checked = git.requireJiraReference,
                onCheckedChange = { onUpdate(git.copy(requireJiraReference = it)) },
                label = "Vyžadovat JIRA referenci v commit zprávě",
            )
            JCheckboxRow(
                checked = git.squashOnMerge,
                onCheckedChange = { onUpdate(git.copy(squashOnMerge = it)) },
                label = "Squash při merge",
            )
        }
    }
}

@Composable
private fun ReviewSection(review: ReviewGuidelinesDto, onUpdate: (ReviewGuidelinesDto) -> Unit) {
    JSection(title = "Review pravidla") {
        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
            JCheckboxRow(
                checked = review.mustHaveTests,
                onCheckedChange = { onUpdate(review.copy(mustHaveTests = it)) },
                label = "Vyžadovat testy",
            )
            JCheckboxRow(
                checked = review.mustPassLint,
                onCheckedChange = { onUpdate(review.copy(mustPassLint = it)) },
                label = "Vyžadovat lint check",
            )
            JTextField(
                value = review.maxChangedFiles?.toString() ?: "",
                onValueChange = { onUpdate(review.copy(maxChangedFiles = it.toIntOrNull())) },
                label = "Max. změněných souborů",
                placeholder = "20",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = review.maxChangedLines?.toString() ?: "",
                onValueChange = { onUpdate(review.copy(maxChangedLines = it.toIntOrNull())) },
                label = "Max. změněných řádků",
                placeholder = "500",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = review.forbiddenFileChanges.joinToString(", "),
                onValueChange = { text ->
                    onUpdate(review.copy(forbiddenFileChanges = text.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                },
                label = "Zakázané soubory (oddělené čárkou)",
                placeholder = ".env, secrets/*, *.key",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = review.focusAreas.joinToString(", "),
                onValueChange = { text ->
                    onUpdate(review.copy(focusAreas = text.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                },
                label = "Focus areas (oddělené čárkou)",
                placeholder = "security, performance, error handling",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CommunicationSection(
    comm: CommunicationGuidelinesDto,
    onUpdate: (CommunicationGuidelinesDto) -> Unit,
) {
    JSection(title = "Komunikační pravidla") {
        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
            JTextField(
                value = comm.emailResponseLanguage ?: "",
                onValueChange = { onUpdate(comm.copy(emailResponseLanguage = it.ifBlank { null })) },
                label = "Jazyk emailových odpovědí",
                placeholder = "cs",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = comm.jiraCommentLanguage ?: "",
                onValueChange = { onUpdate(comm.copy(jiraCommentLanguage = it.ifBlank { null })) },
                label = "Jazyk JIRA komentářů",
                placeholder = "cs",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = comm.formalityLevel ?: "",
                onValueChange = { onUpdate(comm.copy(formalityLevel = it.ifBlank { null })) },
                label = "Úroveň formálnosti",
                placeholder = "formal / informal / neutral",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = comm.emailSignature ?: "",
                onValueChange = { onUpdate(comm.copy(emailSignature = it.ifBlank { null })) },
                label = "E-mail podpis",
                placeholder = "S pozdravem, Jervis",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = comm.customRules.joinToString("\n"),
                onValueChange = { text ->
                    onUpdate(comm.copy(customRules = text.lines().filter { it.isNotBlank() }.map { it.trim() }))
                },
                label = "Vlastní pravidla (jedno na řádek)",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ApprovalSection(approval: ApprovalGuidelinesDto, onUpdate: (ApprovalGuidelinesDto) -> Unit) {
    JSection(title = "Auto-approval pravidla") {
        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
            Text(
                "Povolení automatického schvalování akcí bez dotazu uživatele.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ApprovalRuleRow("Git commit", approval.autoApproveCommit) {
                onUpdate(approval.copy(autoApproveCommit = it))
            }
            ApprovalRuleRow("Git push", approval.autoApprovePush) {
                onUpdate(approval.copy(autoApprovePush = it))
            }
            ApprovalRuleRow("Odeslání emailu", approval.autoApproveEmail) {
                onUpdate(approval.copy(autoApproveEmail = it))
            }
            ApprovalRuleRow("JIRA komentář", approval.autoApproveJiraComment) {
                onUpdate(approval.copy(autoApproveJiraComment = it))
            }
            ApprovalRuleRow("JIRA vytvoření", approval.autoApproveJiraCreate) {
                onUpdate(approval.copy(autoApproveJiraCreate = it))
            }
            ApprovalRuleRow("PR komentář", approval.autoApprovePrComment) {
                onUpdate(approval.copy(autoApprovePrComment = it))
            }
            ApprovalRuleRow("Chat odpověď", approval.autoApproveChatReply) {
                onUpdate(approval.copy(autoApproveChatReply = it))
            }
            ApprovalRuleRow("Confluence update", approval.autoApproveConfluenceUpdate) {
                onUpdate(approval.copy(autoApproveConfluenceUpdate = it))
            }
            ApprovalRuleRow("Spuštění coding agenta", approval.autoApproveCodingDispatch) {
                onUpdate(approval.copy(autoApproveCodingDispatch = it))
            }
        }
    }
}

@Composable
private fun ApprovalRuleRow(label: String, rule: ApprovalRule, onUpdate: (ApprovalRule) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(JervisSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        JSwitch(
            checked = rule.enabled,
            onCheckedChange = { onUpdate(rule.copy(enabled = it)) },
        )
    }
}

@Composable
private fun GeneralSection(general: GeneralGuidelinesDto, onUpdate: (GeneralGuidelinesDto) -> Unit) {
    JSection(title = "Obecná pravidla") {
        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
            JTextField(
                value = general.customRules.joinToString("\n"),
                onValueChange = { text ->
                    onUpdate(general.copy(customRules = text.lines().filter { it.isNotBlank() }.map { it.trim() }))
                },
                label = "Vlastní pravidla (jedno na řádek)",
                placeholder = "Vždy kontroluj bezpečnostní implikace\nPro produkční bugy priorita HIGH",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = general.notes ?: "",
                onValueChange = { onUpdate(general.copy(notes = it.ifBlank { null })) },
                label = "Poznámky",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
