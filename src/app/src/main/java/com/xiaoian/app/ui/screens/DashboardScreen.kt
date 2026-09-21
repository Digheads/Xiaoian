package com.xiaoian.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoian.app.display.ExternalDisplay
import com.xiaoian.app.service.SessionState
import com.xiaoian.app.service.SetupProgress
import com.xiaoian.app.service.StorageInfo
import com.xiaoian.app.terminal.SessionSpec
import com.xiaoian.app.ui.components.AppLogo

@Composable
fun DashboardScreen(
    innerPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: DashboardViewModel = viewModel(),
    onOpenTerminal: (SessionSpec?) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.sessionState.collectAsState()
    val setup by viewModel.setupProgress.collectAsState()
    val xfceStorage by viewModel.xfceStorage.collectAsState()
    val kdeStorage by viewModel.kdeStorage.collectAsState()
    val storageLoading by viewModel.storageLoading.collectAsState()
    val uninstallState by viewModel.uninstallState.collectAsState()
    val externalDisplays by viewModel.externalDisplays.collectAsState()
    val retargeting by viewModel.retargeting.collectAsState()
    val retargetError by viewModel.retargetError.collectAsState()
    
    // Opens on whatever the last started session used, and rememberSaveable so
    // a half-made choice also survives a rotation.
    var selectedDE by rememberSaveable { mutableStateOf(viewModel.lastDe) }
    var selectedMode by rememberSaveable { mutableStateOf(viewModel.lastMode) }

    // Which external display to use. Not remembered across runs: display ids
    // are assigned as screens appear, so yesterday's number means nothing.
    var selectedDisplayId by rememberSaveable { mutableStateOf<Int?>(null) }
    // Drop a choice whose screen has been unplugged, and adopt the only one
    // there is, so the value handed to startSession is always live.
    LaunchedEffect(externalDisplays) {
        if (externalDisplays.none { it.id == selectedDisplayId }) {
            selectedDisplayId = externalDisplays.firstOrNull()?.id
        }
    }
    val selectedDisplay: ExternalDisplay? = externalDisplays.firstOrNull { it.id == selectedDisplayId }

    // Extend and mirror both put the desktop on a screen that has to exist.
    // Without one the script refuses the start outright, so the choice is
    // offered greyed out rather than accepted and then rejected.
    val hasExternalDisplay = externalDisplays.isNotEmpty()
    LaunchedEffect(hasExternalDisplay) {
        if (!hasExternalDisplay && selectedMode != "local") selectedMode = "local"
    }
    var showUninstallDialog by remember { mutableStateOf<String?>(null) }
    
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Balances the gear so the title stays centred.
            Spacer(modifier = Modifier.width(48.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppLogo(modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Xiaoian", style = MaterialTheme.typography.headlineLarge)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        
        // Show Configuration and Start only when Idle
        if (state is SessionState.Idle) {
            // DE Selection
            Text("Desktop Environment", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                RadioButton(selected = selectedDE == "kde", onClick = { selectedDE = "kde" })
                Text("KDE (Wayland)", modifier = Modifier.align(Alignment.CenterVertically))
                Spacer(modifier = Modifier.width(16.dp))
                RadioButton(selected = selectedDE == "xfce", onClick = { selectedDE = "xfce" })
                Text("XFCE (X11)", modifier = Modifier.align(Alignment.CenterVertically))
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Mode Selection
            Text("Display Mode", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeOption("Extend", "extend", selectedMode, hasExternalDisplay) { selectedMode = it }
                Spacer(modifier = Modifier.width(8.dp))
                ModeOption("Mirror", "mirror", selectedMode, hasExternalDisplay) { selectedMode = it }
                Spacer(modifier = Modifier.width(8.dp))
                ModeOption("Local", "local", selectedMode, true) { selectedMode = it }
            }
            if (!hasExternalDisplay) {
                Text(
                    "Connect an external display to use extend or mirror.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Only worth asking when there is something to choose between.
            // With one screen the scripts find it themselves, and in local
            // mode the desktop never leaves the phone.
            if (selectedMode != "local" && externalDisplays.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("External Display", style = MaterialTheme.typography.titleMedium)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    externalDisplays.forEach { display ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedDisplayId = display.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedDisplayId == display.id,
                                onClick = { selectedDisplayId = display.id }
                            )
                            Text(display.label)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { viewModel.startSession(selectedMode, selectedDE, selectedDisplay) },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("START DESKTOP")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Outside the Idle block on purpose: a terminal needs no desktop
        // session, and hiding it while one runs took away the only shell just
        // when it is most useful. Opened with no environment, so the terminal
        // screen asks which one -- or goes straight back to what is open.
        OutlinedButton(
            onClick = { onOpenTerminal(null) },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("TERMINAL")
        }

        Spacer(modifier = Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Environments Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Environments", style = MaterialTheme.typography.titleMedium)
            IconButton(
                onClick = { viewModel.refreshStorage() },
                enabled = !storageLoading
            ) {
                if (storageLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Uninstall in progress overlay
        if (uninstallState.inProgress) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Removing: ${if (uninstallState.de == "kde") "KDE (Wayland)" else "XFCE (X11)"}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        uninstallState.output,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        val context = androidx.compose.ui.platform.LocalContext.current
        // Each desktop has its own Android frontend. One shared lambda used to
        // send both cards to the X11 window, so the KDE card opened an empty
        // Termux:X11 surface instead of the Wayland one.
        val openDesktop = { deId: String ->
            val target = if (deId == "kde")
                com.anland.termux.MainActivity::class.java
            else
                com.termux.x11.MainActivity::class.java
            val intent = android.content.Intent(context, target)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        // XFCE Card
        InstalledEnvironmentCard(
            name = "XFCE (X11)",
            deId = "xfce",
            info = xfceStorage,
            loading = storageLoading,
            sessionState = state,
            selectedDE = selectedDE,
            setupProgress = setup,
            uninstallInProgress = uninstallState.inProgress,
            externalDisplays = externalDisplays,
            retargeting = retargeting,
            retargetError = retargetError,
            onUninstall = { showUninstallDialog = "xfce" },
            onOpenDesktop = { openDesktop("xfce") },
            onLockPhone = { viewModel.lockPhone() },
            onStopSession = { viewModel.stopSession() },
            onRetarget = { mode, display -> viewModel.retarget(mode, display) },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // KDE Card
        InstalledEnvironmentCard(
            name = "KDE (Wayland)",
            deId = "kde",
            info = kdeStorage,
            loading = storageLoading,
            sessionState = state,
            selectedDE = selectedDE,
            setupProgress = setup,
            uninstallInProgress = uninstallState.inProgress,
            externalDisplays = externalDisplays,
            retargeting = retargeting,
            retargetError = retargetError,
            onUninstall = { showUninstallDialog = "kde" },
            onOpenDesktop = { openDesktop("kde") },
            onLockPhone = { viewModel.lockPhone() },
            onStopSession = { viewModel.stopSession() },
            onRetarget = { mode, display -> viewModel.retarget(mode, display) },
        )
    }

    // Uninstall Confirmation Dialog
    showUninstallDialog?.let { de ->
        val envName = if (de == "kde") "KDE (Wayland)" else "XFCE (X11)"
        AlertDialog(
            onDismissRequest = { showUninstallDialog = null },
            title = { Text("Uninstall Environment") },
            text = {
                Text("Are you sure you want to remove the $envName environment?\n\nThis action cannot be undone. The entire Debian rootfs and all installer files will be deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUninstallDialog = null
                        viewModel.uninstall(de)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun InstalledEnvironmentCard(
    name: String,
    deId: String,
    info: StorageInfo?,
    loading: Boolean,
    sessionState: SessionState,
    selectedDE: String,
    setupProgress: SetupProgress,
    uninstallInProgress: Boolean,
    externalDisplays: List<ExternalDisplay>,
    retargeting: Boolean,
    retargetError: String?,
    onUninstall: () -> Unit,
    onOpenDesktop: () -> Unit,
    onLockPhone: () -> Unit,
    onStopSession: () -> Unit,
    onRetarget: (String, ExternalDisplay?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (info?.installed == true)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                if (info?.installed == true && sessionState is SessionState.Idle) {
                    IconButton(
                        onClick = onUninstall,
                        enabled = !uninstallInProgress
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (loading && info == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            } else if (info?.installed == true) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    StorageInfo.formatSize(info.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    "Not installed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            
            // Check if this card represents the active/starting/stopping session
            val isActiveDE = when (sessionState) {
                is SessionState.Starting -> selectedDE == deId
                is SessionState.Running -> sessionState.de == deId
                is SessionState.Stopping -> selectedDE == deId
                is SessionState.Error -> selectedDE == deId
                else -> false
            }

            // No TERMINAL button here. The one above the cards opens the
            // terminal screen, and the environment is chosen there -- having
            // both meant three buttons that led to the same place.
            if (isActiveDE) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(16.dp))
                
                when (sessionState) {
                    is SessionState.Starting -> {
                        Text("Status: Starting...", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        SetupProgressView(setupProgress)
                    }
                    is SessionState.Running -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Status: ", fontWeight = FontWeight.Bold)
                            Text("RUNNING", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Text("Display mode: ${sessionState.mode}")

                        Spacer(modifier = Modifier.height(12.dp))

                        // Lets a running session move between local/extend/mirror
                        // without a full stop+start. Seeded fresh on every mode
                        // change so switching DE cards (each recomposes on its
                        // own sessionState.mode) never carries a stale target.
                        var retargetMode by remember(sessionState.mode) { mutableStateOf(sessionState.mode) }
                        var retargetDisplayId by remember(sessionState.mode) {
                            mutableStateOf(externalDisplays.firstOrNull()?.id)
                        }
                        val retargetDisplay = externalDisplays.firstOrNull { it.id == retargetDisplayId }
                        val hasDisplay = externalDisplays.isNotEmpty()

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ModeOption("Extend", "extend", retargetMode, hasDisplay) { retargetMode = it }
                            Spacer(modifier = Modifier.width(8.dp))
                            ModeOption("Mirror", "mirror", retargetMode, hasDisplay) { retargetMode = it }
                            Spacer(modifier = Modifier.width(8.dp))
                            ModeOption("Local", "local", retargetMode, true) { retargetMode = it }
                        }
                        if (retargetMode != "local" && externalDisplays.size > 1) {
                            Column {
                                externalDisplays.forEach { display ->
                                    Row(
                                        modifier = Modifier
                                            .clickable { retargetDisplayId = display.id }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = retargetDisplayId == display.id,
                                            onClick = { retargetDisplayId = display.id }
                                        )
                                        Text(display.label)
                                    }
                                }
                            }
                        }
                        if (retargeting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Switching...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onRetarget(retargetMode, retargetDisplay) },
                                enabled = retargetMode != sessionState.mode,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("SWITCH TO ${retargetMode.uppercase()}")
                            }
                        }
                        retargetError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Three equal buttons in one row already did not fit on
                        // a phone, and LOCK makes four. The primary action gets
                        // the full width; the rest share the row below it.
                        Button(
                            onClick = onOpenDesktop,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("OPEN DESKTOP")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Locking the phone only means anything while the
                            // desktop lives on another display.
                            if (sessionState.mode != "local") {
                                OutlinedButton(onClick = onLockPhone, modifier = Modifier.weight(1f)) {
                                    Text("LOCK")
                                }
                            }
                            Button(
                                onClick = onStopSession,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("STOP")
                            }
                        }
                    }
                    is SessionState.Stopping -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Stopping session...")
                        }
                    }
                    is SessionState.Error -> {
                        Text("Status: ERROR", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        Text(sessionState.message, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onStopSession, modifier = Modifier.fillMaxWidth()) {
                            Text("RESET")
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

/**
 * One display-mode radio button.
 *
 * Exists so the disabled state is written once: a plain [Text] beside a
 * disabled [RadioButton] stays fully opaque, which reads as enabled, so the
 * label has to be dimmed by hand.
 */
@Composable
private fun ModeOption(
    label: String,
    value: String,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    RadioButton(
        selected = selected == value,
        enabled = enabled,
        onClick = { onSelect(value) }
    )
    Text(
        label,
        // Material's disabled-content alpha; the Row centres it vertically.
        color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    )
}
