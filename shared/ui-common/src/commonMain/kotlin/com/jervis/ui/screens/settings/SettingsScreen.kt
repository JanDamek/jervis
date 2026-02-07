package com.jervis.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.screens.settings.sections.*

@Composable
fun SettingsScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.GENERAL) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar
        SettingsSidebar(
            selectedCategory = selectedCategory,
            onCategorySelected = { selectedCategory = it },
            onBack = onBack,
        )

        // Vertical Divider
        VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // Content
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SettingsContent(
                category = selectedCategory,
                repository = repository,
            )
        }
    }
}

enum class SettingsCategory(
    val title: String,
    val icon: String,
    val description: String,
) {
    GENERAL("Obecné", "⚙️", "Základní nastavení aplikace a vzhledu."),
    CLIENTS("Klienti", "🏢", "Správa organizačních jednotek."),
    PROJECTS("Projekty", "📁", "Správa projektů přiřazených klientům."),
    CONNECTIONS("Připojení", "🔌", "Technické parametry připojení (Atlassian, Git, Email)."),
    CODING_AGENTS("Coding Agenti", "🤖", "Nastavení API klíčů a konfigurace coding agentů (Claude, Junie, Aider)."),
    LOGS("Logy", "📜", "Chybové logy a diagnostika."),
}

@Composable
private fun SettingsSidebar(
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(220.dp)
                .fillMaxHeight()
                .padding(vertical = 16.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Zpět")
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsCategory.values().forEach { category ->
            SidebarItem(
                category = category,
                isSelected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
            )
        }
    }
}

@Composable
private fun SidebarItem(
    category: SettingsCategory,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(category.icon, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = category.title,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun GeneralSettings(repository: JervisRepository) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        JSection(title = "Vzhled") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Téma aplikace")
                Spacer(modifier = Modifier.weight(1f))
                Text("Systémové", style = MaterialTheme.typography.bodySmall)
            }
        }
        JSection(title = "Lokalizace") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Jazyk")
                Spacer(modifier = Modifier.weight(1f))
                Text("Čeština", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsContent(
    category: SettingsCategory,
    repository: JervisRepository,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = category.title,
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = category.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (category) {
                    SettingsCategory.GENERAL -> {
                        GeneralSettings(repository)
                    }

                    SettingsCategory.CLIENTS -> {
                        ClientsSettings(repository)
                    }

                    SettingsCategory.PROJECTS -> {
                        ProjectsSettings(repository)
                    }

                    SettingsCategory.CONNECTIONS -> {
                        ConnectionsSettings(repository)
                    }

                    SettingsCategory.CODING_AGENTS -> {
                        CodingAgentsSettings(repository)
                    }

                    SettingsCategory.LOGS -> {
                        LogsSettings(repository)
                    }

                    else -> {
                        Text("Obsah pro ${category.title} bude implementován brzy...")
                    }
                }
            }
        }
    }
}

// Helper for scrollable content
@Composable
fun rememberScrollState() = androidx.compose.foundation.rememberScrollState()
