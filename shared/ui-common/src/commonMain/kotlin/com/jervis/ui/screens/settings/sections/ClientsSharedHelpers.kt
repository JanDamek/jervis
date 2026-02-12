package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.ui.design.JCheckboxRow
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing

internal fun getCapabilityLabel(capability: ConnectionCapability): String {
    return when (capability) {
        ConnectionCapability.BUGTRACKER -> "Bug Tracker"
        ConnectionCapability.WIKI -> "Wiki"
        ConnectionCapability.REPOSITORY -> "Repository"
        ConnectionCapability.EMAIL_READ -> "Email (Read)"
        ConnectionCapability.EMAIL_SEND -> "Email (Send)"
    }
}

internal fun getIndexAllLabel(capability: ConnectionCapability): String {
    return when (capability) {
        ConnectionCapability.BUGTRACKER -> "Indexovat všechny projekty"
        ConnectionCapability.WIKI -> "Indexovat všechny prostory"
        ConnectionCapability.EMAIL_READ -> "Indexovat celou schránku"
        ConnectionCapability.EMAIL_SEND -> "Použít všechny odesílatele"
        ConnectionCapability.REPOSITORY -> "Indexovat všechny repozitáře"
    }
}

/**
 * Shared Git commit configuration form fields.
 * Used by both ClientEditForm and ProjectEditForm to avoid duplication.
 */
@Composable
internal fun GitCommitConfigFields(
    messageFormat: String,
    onMessageFormatChange: (String) -> Unit,
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
) {
    JTextField(
        value = messageFormat,
        onValueChange = onMessageFormatChange,
        label = "Formát commit message (volitelné)",
        placeholder = "[{project}] {message}",
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
        JTextField(
            value = gpgKeyId,
            onValueChange = onGpgKeyIdChange,
            label = "GPG Key ID",
            placeholder = "např. ABCD1234",
        )
    }
}
