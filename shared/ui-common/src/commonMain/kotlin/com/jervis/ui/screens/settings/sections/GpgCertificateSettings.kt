package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import com.jervis.dto.coding.GpgCertificateDeleteDto
import com.jervis.dto.coding.GpgCertificateDto
import com.jervis.dto.coding.GpgCertificateUploadDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JStatusBadge
import com.jervis.ui.design.JTextField
import com.jervis.ui.util.SystemGpgKey
import com.jervis.ui.util.exportSystemGpgKey
import com.jervis.ui.util.listSystemGpgKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GpgCertificateSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var certificates by remember { mutableStateOf<List<GpgCertificateDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun loadCertificates() {
        try {
            certificates = repository.gpgCertificates.getAllCertificates()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání certifikátů: ${e.message}")
        }
    }

    LaunchedEffect(Unit) {
        loadCertificates()
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            JCenteredLoading()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // System GPG import (Desktop only — returns empty on Android/iOS)
                item {
                    GpgSystemImport(
                        onImport = { keyId, userName, userEmail, armoredKey, passphrase ->
                            scope.launch {
                                try {
                                    repository.gpgCertificates.uploadCertificate(
                                        GpgCertificateUploadDto(
                                            keyId = keyId,
                                            userName = userName,
                                            userEmail = userEmail,
                                            privateKeyArmored = armoredKey,
                                            passphrase = passphrase.ifBlank { null },
                                        ),
                                    )
                                    loadCertificates()
                                    snackbarHostState.showSnackbar("GPG klíč importován ze systému")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Chyba importu: ${e.message}")
                                }
                            }
                        },
                        snackbarHostState = snackbarHostState,
                    )
                }

                // Existing certificates (global list)
                if (certificates.isEmpty()) {
                    item {
                        JEmptyState(
                            message = "Žádné GPG certifikáty\nNahrajte GPG klíč pro podepisování commitů coding agentů.",
                            icon = Icons.Default.Lock,
                        )
                    }
                } else {
                    items(certificates) { cert ->
                        GpgCertificateCard(
                            certificate = cert,
                            onDelete = {
                                scope.launch {
                                    try {
                                        repository.gpgCertificates.deleteCertificate(
                                            GpgCertificateDeleteDto(id = cert.id),
                                        )
                                        loadCertificates()
                                        snackbarHostState.showSnackbar("Certifikát smazán")
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                    }
                                }
                            },
                        )
                    }
                }

                // Manual upload form
                item {
                    GpgUploadForm(
                        onUpload = { keyId, userName, userEmail, privateKey, passphrase ->
                            scope.launch {
                                try {
                                    repository.gpgCertificates.uploadCertificate(
                                        GpgCertificateUploadDto(
                                            keyId = keyId,
                                            userName = userName,
                                            userEmail = userEmail,
                                            privateKeyArmored = privateKey,
                                            passphrase = passphrase.ifBlank { null },
                                        ),
                                    )
                                    loadCertificates()
                                    snackbarHostState.showSnackbar("GPG certifikát nahrán")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                }
                            }
                        },
                    )
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
private fun GpgSystemImport(
    onImport: (keyId: String, userName: String, userEmail: String, armoredKey: String, passphrase: String) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var systemKeys by remember { mutableStateOf<List<SystemGpgKey>>(emptyList()) }
    var selectedKey by remember { mutableStateOf<SystemGpgKey?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var isDetecting by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        systemKeys = withContext(Dispatchers.Default) { listSystemGpgKeys() }
        isDetecting = false
    }

    // Don't show this section if GPG is not available (Android/iOS)
    if (!isDetecting && systemKeys.isEmpty()) return

    JCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Import ze systému", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Importovat GPG klíč z lokálního keyringu",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            if (isDetecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Načítám systémové GPG klíče...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                JDropdown(
                    items = systemKeys,
                    selectedItem = selectedKey,
                    onItemSelected = { selectedKey = it },
                    label = "Systémový GPG klíč",
                    itemLabel = { "${it.userName} <${it.userEmail}> (${it.keyId.takeLast(8)})" },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                JTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = "Passphrase (volitelné)",
                    placeholder = "Pouze pokud je klíč chráněný heslem",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                JPrimaryButton(
                    onClick = {
                        val key = selectedKey ?: return@JPrimaryButton
                        isExporting = true
                        scope.launch {
                            val armored = withContext(Dispatchers.Default) {
                                exportSystemGpgKey(key.keyId)
                            }
                            isExporting = false
                            if (armored != null) {
                                onImport(key.keyId, key.userName, key.userEmail, armored, passphrase)
                                selectedKey = null
                                passphrase = ""
                            } else {
                                snackbarHostState.showSnackbar("Export GPG klíče selhal — zkontrolujte gpg a passphrase")
                            }
                        }
                    },
                    enabled = selectedKey != null && !isExporting,
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Importovat klíč")
                    }
                }
            }
        }
    }
}

@Composable
private fun GpgCertificateCard(
    certificate: GpgCertificateDto,
    onDelete: () -> Unit,
) {
    JCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
            ) {
                Text("GPG klíč", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                JStatusBadge(
                    status = if (certificate.hasPrivateKey) "ACTIVE" else "DISCONNECTED",
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Key ID: ${certificate.keyId}", style = MaterialTheme.typography.bodySmall)
            Text("Jméno: ${certificate.userName}", style = MaterialTheme.typography.bodySmall)
            Text("Email: ${certificate.userEmail}", style = MaterialTheme.typography.bodySmall)
            if (certificate.createdAt.isNotBlank()) {
                Text(
                    "Vytvořeno: ${certificate.createdAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JSecondaryButton(onClick = onDelete) {
                    Text("Smazat")
                }
            }
        }
    }
}

@Composable
private fun GpgUploadForm(
    onUpload: (keyId: String, userName: String, userEmail: String, privateKey: String, passphrase: String) -> Unit,
) {
    var keyId by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var userEmail by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    JCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Ruční nahrání GPG certifikátu", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(12.dp))

            JSection(title = "Identifikace klíče") {
                JTextField(
                    value = keyId,
                    onValueChange = { keyId = it },
                    label = "Key ID (fingerprint)",
                    placeholder = "ABCDEF1234567890",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    JTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = "Jméno",
                        placeholder = "Jervis Bot",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    JTextField(
                        value = userEmail,
                        onValueChange = { userEmail = it },
                        label = "Email",
                        placeholder = "bot@example.com",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            JSection(title = "Privátní klíč") {
                JTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = "ASCII Armored Private Key",
                    placeholder = "-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----",
                    singleLine = false,
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                JTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = "Passphrase (volitelné)",
                    placeholder = "Pouze pokud je klíč chráněný heslem",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))

            JPrimaryButton(
                onClick = {
                    onUpload(keyId, userName, userEmail, privateKey, passphrase)
                    keyId = ""
                    userName = ""
                    userEmail = ""
                    privateKey = ""
                    passphrase = ""
                },
                enabled = keyId.isNotBlank() && userName.isNotBlank() &&
                    userEmail.isNotBlank() && privateKey.isNotBlank(),
            ) {
                Text("Nahrát certifikát")
            }
        }
    }
}
