package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.coding.GpgCertificateDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.ui.design.JCheckboxRow
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing

internal fun getCapabilityLabel(capability: ConnectionCapability): String {
    return when (capability) {
        ConnectionCapability.BUGTRACKER -> "Bug Tracker"
        ConnectionCapability.WIKI -> "Wiki"
        ConnectionCapability.REPOSITORY -> "Repository"
        ConnectionCapability.EMAIL_READ -> "Email (Read)"
        ConnectionCapability.EMAIL_SEND -> "Email (Send)"
        ConnectionCapability.CHAT_READ -> "Chat (čtení)"
        ConnectionCapability.CHAT_SEND -> "Chat (odesílání)"
        ConnectionCapability.CALENDAR_READ -> "Kalendář (čtení)"
        ConnectionCapability.CALENDAR_WRITE -> "Kalendář (zápis)"
    }
}

internal fun getIndexAllLabel(capability: ConnectionCapability): String {
    return when (capability) {
        ConnectionCapability.BUGTRACKER -> "Indexovat všechny projekty"
        ConnectionCapability.WIKI -> "Indexovat všechny prostory"
        ConnectionCapability.EMAIL_READ -> "Indexovat celou schránku"
        ConnectionCapability.EMAIL_SEND -> "Použít všechny odesílatele"
        ConnectionCapability.REPOSITORY -> "Indexovat všechny repozitáře"
        ConnectionCapability.CHAT_READ -> "Indexovat všechny kanály"
        ConnectionCapability.CHAT_SEND -> "Použít všechny kanály"
        ConnectionCapability.CALENDAR_READ -> "Indexovat všechny kalendáře"
        ConnectionCapability.CALENDAR_WRITE -> "Spravovat všechny kalendáře"
    }
}

@Composable
internal fun GitCommitConfigFields(
    messageFormat: String,
    onMessageFormatChange: (String) -> Unit,
    messagePattern: String,
    onMessagePatternChange: (String) -> Unit,
    authorName: String,
    onAuthorNameChange: (String) -> Unit,
    authorEmail: String,
    onAuthorEmailChange: (String) -> Unit,
    committerName: String,
    onCommitterNameChange: (String) -> Unit,
    committerEmail: String,
    onCommitterEmailChange: (String) -> Unit,
    gpgSign: Boolean,
    onGpgSignChange: (Boolean) -> Unit,
    gpgKeyId: String,
    onGpgKeyIdChange: (String) -> Unit,
    gpgCertificates: List<GpgCertificateDto> = emptyList(),
) {
    JTextField(
        value = messageFormat,
        onValueChange = onMessageFormatChange,
        label = "Formát commit message (volitelné)",
        placeholder = "[{project}] {message}\n\nDetailed description...",
        singleLine = false,
        minLines = 2,
        maxLines = 6,
    )

    Spacer(Modifier.height(JervisSpacing.itemGap))

    JTextField(
        value = messagePattern,
        onValueChange = onMessagePatternChange,
        label = "Pattern s placeholdery (volitelné)",
        placeholder = "[\$project] \$message\n\n\$detail",
        singleLine = false,
        minLines = 2,
        maxLines = 6,
    )

    Text(
        "Dostupné: \$task_number, \$project, \$message, \$detail, \$author, \$date",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(JervisSpacing.itemGap))

    JTextField(
        value = authorName,
        onValueChange = onAuthorNameChange,
        label = "Jméno autora",
        placeholder = "Agent Name",
    )

    Spacer(Modifier.height(JervisSpacing.itemGap))

    JTextField(
        value = authorEmail,
        onValueChange = onAuthorEmailChange,
        label = "Email autora",
        placeholder = "agent@example.com",
    )

    Spacer(Modifier.height(12.dp))

    Text(
        "Committer (ponechte prázdné pro použití autora)",
        style = MaterialTheme.typography.labelMedium,
    )

    Spacer(Modifier.height(JervisSpacing.itemGap))

    JTextField(
        value = committerName,
        onValueChange = onCommitterNameChange,
        label = "Jméno committera (volitelné)",
    )

    Spacer(Modifier.height(JervisSpacing.itemGap))

    JTextField(
        value = committerEmail,
        onValueChange = onCommitterEmailChange,
        label = "Email committera (volitelné)",
    )

    Spacer(Modifier.height(12.dp))

    JCheckboxRow(
        label = "GPG podpis commitů",
        checked = gpgSign,
        onCheckedChange = onGpgSignChange,
    )

    if (gpgSign) {
        Spacer(Modifier.height(JervisSpacing.itemGap))
        if (gpgCertificates.isNotEmpty()) {
            val selectedCert = gpgCertificates.find { it.keyId == gpgKeyId }
            JDropdown(
                items = gpgCertificates,
                selectedItem = selectedCert,
                onItemSelected = { onGpgKeyIdChange(it.keyId) },
                label = "GPG klíč",
                itemLabel = { "${it.keyId} (${it.userName} <${it.userEmail}>)" },
            )
        } else {
            JTextField(
                value = gpgKeyId,
                onValueChange = onGpgKeyIdChange,
                label = "GPG Key ID",
                placeholder = "např. ABCD1234",
            )
        }
    }
}
