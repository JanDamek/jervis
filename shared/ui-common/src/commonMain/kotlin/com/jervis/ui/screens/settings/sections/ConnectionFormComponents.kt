package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jervis.dto.connection.AuthOption
import com.jervis.dto.connection.FormFieldType
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.dto.connection.ProviderEnum
import com.jervis.ui.design.JCheckboxRow
import com.jervis.ui.design.JTextField

@Composable
internal fun ProviderDropdown(
    selected: ProviderEnum,
    descriptors: Map<ProviderEnum, ProviderDescriptor>,
    onSelect: (ProviderEnum) -> Unit,
) {
    // Only show providers that have a descriptor (= implemented)
    val availableProviders = ProviderEnum.entries.filter { it in descriptors }
    @OptIn(ExperimentalMaterial3Api::class)
    JDropdownRaw(
        value = descriptors[selected]?.displayName ?: selected.name,
        label = "Typ připojení",
        items = availableProviders,
        itemLabel = { descriptors[it]?.displayName ?: it.name },
        onSelect = onSelect,
    )
}

@Composable
internal fun AuthOptionDropdown(
    selected: AuthOption?,
    options: List<AuthOption>,
    onSelect: (AuthOption) -> Unit,
) {
    @OptIn(ExperimentalMaterial3Api::class)
    JDropdownRaw(
        value = selected?.displayName ?: "",
        label = "Metoda autentizace",
        items = options,
        itemLabel = { it.displayName },
        onSelect = onSelect,
    )
}

/**
 * Generic dropdown backed by ExposedDropdownMenuBox.
 * Used for ProviderEnum and AuthOption where we need raw control.
 * Cannot use JTextField because menuAnchor() must be on the OutlinedTextField directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> JDropdownRaw(
    value: String,
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun FormFields(
    fields: List<com.jervis.dto.connection.FormField>,
    fieldValues: MutableMap<FormFieldType, String>,
    availableProtocols: Set<ProtocolEnum>,
) {
    val isCloud = fieldValues[FormFieldType.CLOUD_TOGGLE] == "true"

    for (field in fields) {
        when (field.type) {
            FormFieldType.CLOUD_TOGGLE -> {
                JCheckboxRow(
                    label = field.label,
                    checked = fieldValues[FormFieldType.CLOUD_TOGGLE] == "true",
                    onCheckedChange = { fieldValues[FormFieldType.CLOUD_TOGGLE] = it.toString() },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            FormFieldType.BASE_URL -> {
                // Hide base URL when cloud toggle is on
                if (!isCloud) {
                    JTextField(
                        value = fieldValues[FormFieldType.BASE_URL] ?: "",
                        onValueChange = { fieldValues[FormFieldType.BASE_URL] = it },
                        label = field.label,
                        placeholder = field.placeholder.ifEmpty { null },
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            FormFieldType.USE_SSL -> {
                JCheckboxRow(
                    label = field.label,
                    checked = fieldValues[FormFieldType.USE_SSL] != "false",
                    onCheckedChange = { fieldValues[FormFieldType.USE_SSL] = it.toString() },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            @OptIn(ExperimentalMaterial3Api::class)
            FormFieldType.PROTOCOL -> {
                if (availableProtocols.size > 1) {
                    val currentProtocol = fieldValues[FormFieldType.PROTOCOL]
                        ?: availableProtocols.firstOrNull()?.name ?: ""
                    JDropdownRaw(
                        value = currentProtocol,
                        label = field.label,
                        items = availableProtocols.toList(),
                        itemLabel = { it.name },
                        onSelect = { fieldValues[FormFieldType.PROTOCOL] = it.name },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            else -> {
                // Text fields: USERNAME, PASSWORD, BEARER_TOKEN, HOST, PORT, FOLDER_NAME
                JTextField(
                    value = fieldValues[field.type] ?: "",
                    onValueChange = { fieldValues[field.type] = it },
                    label = field.label,
                    placeholder = field.placeholder.ifEmpty { null },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None, // Private app — secrets always visible (docs/ui-design.md)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Key-value mapping editor for sender/domain → client ID mappings.
 * Shows existing entries with delete, and an "add" row at the bottom.
 */
@Composable
internal fun KeyValueMappingSection(
    title: String,
    description: String,
    keyLabel: String,
    valueLabel: String,
    mappings: SnapshotStateMap<String, String>,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Existing entries
        for ((key, value) in mappings.toList()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                JTextField(
                    value = key,
                    onValueChange = {},
                    label = keyLabel,
                    singleLine = true,
                    readOnly = true,
                    modifier = Modifier.weight(1f),
                )
                JTextField(
                    value = value,
                    onValueChange = {},
                    label = valueLabel,
                    singleLine = true,
                    readOnly = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { mappings.remove(key) },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Odebrat",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
        }

        // Add new entry row
        var newKey by remember { mutableStateOf("") }
        var newValue by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            JTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = keyLabel,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            JTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = valueLabel,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (newKey.isNotBlank() && newValue.isNotBlank()) {
                        mappings[newKey.trim()] = newValue.trim()
                        newKey = ""
                        newValue = ""
                    }
                },
                modifier = Modifier.size(44.dp),
                enabled = newKey.isNotBlank() && newValue.isNotBlank(),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Přidat",
                )
            }
        }
    }
}
