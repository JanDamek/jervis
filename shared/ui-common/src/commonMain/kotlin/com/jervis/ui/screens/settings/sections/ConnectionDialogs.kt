package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.dto.connection.FormFieldType
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.dto.connection.ProviderEnum
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JTextField

@Composable
internal fun ConnectionCreateDialog(
    descriptors: Map<ProviderEnum, ProviderDescriptor>,
    onDismiss: () -> Unit,
    onCreate: (ConnectionCreateRequestDto) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(ProviderEnum.GITHUB) }

    val descriptor = descriptors[provider]
    val authOptions = descriptor?.authOptions ?: emptyList()
    var selectedAuthOption by remember { mutableStateOf(authOptions.firstOrNull()) }

    // Field values keyed by FormFieldType
    val fieldValues = remember { mutableStateMapOf<FormFieldType, String>() }

    // Reset auth option and field values when provider changes
    LaunchedEffect(provider) {
        val newAuthOptions = descriptors[provider]?.authOptions ?: emptyList()
        selectedAuthOption = newAuthOptions.firstOrNull()
        fieldValues.clear()
        // Initialize defaults
        selectedAuthOption?.fields?.forEach { field ->
            if (field.defaultValue.isNotEmpty()) {
                fieldValues[field.type] = field.defaultValue
            }
        }
    }

    // Reset field values when auth option changes
    LaunchedEffect(selectedAuthOption) {
        fieldValues.clear()
        selectedAuthOption?.fields?.forEach { field ->
            if (field.defaultValue.isNotEmpty()) {
                fieldValues[field.type] = field.defaultValue
            }
        }
    }

    val authType = selectedAuthOption?.authType ?: AuthTypeEnum.NONE
    val fields = selectedAuthOption?.fields ?: emptyList()
    val isCloud = fieldValues[FormFieldType.CLOUD_TOGGLE] == "true"

    // Validation: all required fields must be non-blank (CLOUD_TOGGLE and USE_SSL are always valid)
    val enabled = name.isNotBlank() && fields.all { field ->
        !field.required ||
            field.type == FormFieldType.CLOUD_TOGGLE ||
            field.type == FormFieldType.USE_SSL ||
            field.type == FormFieldType.PROTOCOL ||
            // BASE_URL is not required when cloud toggle is on
            (field.type == FormFieldType.BASE_URL && isCloud) ||
            fieldValues[field.type]?.isNotBlank() == true
    }

    JFormDialog(
        visible = true,
        title = "Vytvořit nové připojení",
        onConfirm = {
            val protocol = fieldValues[FormFieldType.PROTOCOL]?.let { ProtocolEnum.valueOf(it) }
                ?: descriptor?.protocols?.firstOrNull() ?: ProtocolEnum.HTTP
            val request = ConnectionCreateRequestDto(
                provider = provider,
                protocol = protocol,
                authType = authType,
                name = name,
                isCloud = isCloud,
                baseUrl = fieldValues[FormFieldType.BASE_URL]?.takeIf { it.isNotBlank() },
                username = fieldValues[FormFieldType.USERNAME]?.takeIf { it.isNotBlank() },
                password = fieldValues[FormFieldType.PASSWORD]?.takeIf { it.isNotBlank() },
                bearerToken = fieldValues[FormFieldType.BEARER_TOKEN]?.takeIf { it.isNotBlank() },
                host = fieldValues[FormFieldType.HOST]?.takeIf { it.isNotBlank() },
                port = fieldValues[FormFieldType.PORT]?.toIntOrNull(),
                useSsl = fieldValues[FormFieldType.USE_SSL]?.let { it == "true" },
                folderName = fieldValues[FormFieldType.FOLDER_NAME]?.takeIf { it.isNotBlank() },
            )
            onCreate(request)
        },
        onDismiss = onDismiss,
        confirmEnabled = enabled,
        confirmText = if (authType == AuthTypeEnum.OAUTH2) "Authorizovat" else "Vytvořit",
    ) {
        // Connection name
        JTextField(
            value = name,
            onValueChange = { name = it },
            label = "Název připojení",
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Provider dropdown
        ProviderDropdown(
            selected = provider,
            descriptors = descriptors,
            onSelect = { provider = it },
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Auth option dropdown (if multiple)
        if (authOptions.size > 1) {
            AuthOptionDropdown(
                selected = selectedAuthOption,
                options = authOptions,
                onSelect = { selectedAuthOption = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // OAuth2 info text when no fields
        if (authType == AuthTypeEnum.OAUTH2 && fields.isEmpty()) {
            Text(
                "Přihlašovací údaje pro OAuth2 jsou spravovány automaticky serverem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Render fields from auth option definition
        FormFields(
            fields = fields,
            fieldValues = fieldValues,
            availableProtocols = descriptor?.protocols ?: setOf(ProtocolEnum.HTTP),
        )
    }
}

@Composable
internal fun ConnectionEditDialog(
    connection: ConnectionResponseDto,
    descriptors: Map<ProviderEnum, ProviderDescriptor>,
    onDismiss: () -> Unit,
    onSave: (String, ConnectionUpdateRequestDto) -> Unit,
) {
    var name by remember { mutableStateOf(connection.name) }
    val state = connection.state
    var provider by remember { mutableStateOf(connection.provider) }

    val descriptor = descriptors[provider]
    val authOptions = descriptor?.authOptions ?: emptyList()
    var selectedAuthOption by remember {
        mutableStateOf(authOptions.firstOrNull { it.authType == connection.authType } ?: authOptions.firstOrNull())
    }

    // Field values initialized from connection
    val fieldValues = remember {
        mutableStateMapOf<FormFieldType, String>().apply {
            connection.baseUrl?.let { put(FormFieldType.BASE_URL, it) }
            connection.username?.let { put(FormFieldType.USERNAME, it) }
            connection.password?.let { put(FormFieldType.PASSWORD, it) }
            connection.bearerToken?.let { put(FormFieldType.BEARER_TOKEN, it) }
            connection.host?.let { put(FormFieldType.HOST, it) }
            connection.port?.let { put(FormFieldType.PORT, it.toString()) }
            connection.useSsl?.let { put(FormFieldType.USE_SSL, it.toString()) }
            connection.folderName?.let { put(FormFieldType.FOLDER_NAME, it) }
            put(FormFieldType.PROTOCOL, connection.protocol.name)
            put(FormFieldType.CLOUD_TOGGLE, connection.isCloud.toString())
        }
    }

    // Track if this is the initial load to avoid clearing saved values
    var isInitialLoad by remember { mutableStateOf(true) }

    // Reset field values when auth option changes, but preserve existing values on initial load
    LaunchedEffect(selectedAuthOption) {
        if (isInitialLoad) {
            // On initial load, don't clear - we want to keep connection values
            isInitialLoad = false
        } else {
            // User changed auth option - preserve all existing values that aren't auth-specific
            val preserved = fieldValues.toMap()
            fieldValues.clear()
            // Re-initialize defaults from new auth option
            selectedAuthOption?.fields?.forEach { field ->
                if (field.defaultValue.isNotEmpty() && !preserved.containsKey(field.type)) {
                    fieldValues[field.type] = field.defaultValue
                }
            }
            // Restore preserved values
            preserved.forEach { (key, value) ->
                if (key == FormFieldType.CLOUD_TOGGLE || selectedAuthOption?.fields?.any { it.type == key } == true) {
                    fieldValues[key] = value
                }
            }
        }
    }

    val authType = selectedAuthOption?.authType ?: AuthTypeEnum.NONE
    val fields = selectedAuthOption?.fields ?: emptyList()
    val isCloud = fieldValues[FormFieldType.CLOUD_TOGGLE] == "true"

    val enabled = name.isNotBlank() && fields.all { field ->
        !field.required ||
            field.type == FormFieldType.CLOUD_TOGGLE ||
            field.type == FormFieldType.USE_SSL ||
            field.type == FormFieldType.PROTOCOL ||
            (field.type == FormFieldType.BASE_URL && isCloud) ||
            fieldValues[field.type]?.isNotBlank() == true
    }

    JFormDialog(
        visible = true,
        title = "Upravit připojení",
        onConfirm = {
            val protocol = fieldValues[FormFieldType.PROTOCOL]?.let { ProtocolEnum.valueOf(it) }
                ?: descriptor?.protocols?.firstOrNull() ?: ProtocolEnum.HTTP
            val request = ConnectionUpdateRequestDto(
                name = name,
                provider = provider,
                protocol = protocol,
                authType = authType,
                state = state,
                isCloud = isCloud,
                baseUrl = fieldValues[FormFieldType.BASE_URL]?.takeIf { it.isNotBlank() },
                username = fieldValues[FormFieldType.USERNAME]?.takeIf { it.isNotBlank() },
                password = fieldValues[FormFieldType.PASSWORD]?.takeIf { it.isNotBlank() },
                bearerToken = fieldValues[FormFieldType.BEARER_TOKEN]?.takeIf { it.isNotBlank() },
                host = fieldValues[FormFieldType.HOST]?.takeIf { it.isNotBlank() },
                port = fieldValues[FormFieldType.PORT]?.toIntOrNull(),
                useSsl = fieldValues[FormFieldType.USE_SSL]?.let { it == "true" },
                folderName = fieldValues[FormFieldType.FOLDER_NAME]?.takeIf { it.isNotBlank() },
            )
            onSave(connection.id, request)
        },
        onDismiss = onDismiss,
        confirmEnabled = enabled,
        confirmText = "Uložit",
    ) {
        JTextField(
            value = name,
            onValueChange = { name = it },
            label = "Název připojení",
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))

        ProviderDropdown(
            selected = provider,
            descriptors = descriptors,
            onSelect = { provider = it },
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (authOptions.size > 1) {
            AuthOptionDropdown(
                selected = selectedAuthOption,
                options = authOptions,
                onSelect = { selectedAuthOption = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (authType == AuthTypeEnum.OAUTH2 && fields.isEmpty()) {
            Text(
                "Přihlašovací údaje pro OAuth2 jsou spravovány automaticky serverem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        FormFields(
            fields = fields,
            fieldValues = fieldValues,
            availableProtocols = descriptor?.protocols ?: setOf(ProtocolEnum.HTTP),
        )
    }
}
