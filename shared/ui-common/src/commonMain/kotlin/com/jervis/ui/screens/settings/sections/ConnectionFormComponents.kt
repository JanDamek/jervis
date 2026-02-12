package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    @OptIn(ExperimentalMaterial3Api::class)
    JDropdownRaw(
        value = descriptors[selected]?.displayName ?: selected.name,
        label = "Provider",
        items = ProviderEnum.entries.toList(),
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
                    visualTransformation = if (field.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
