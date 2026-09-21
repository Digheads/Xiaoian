package com.xiaoian.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoian.app.device.DeviceSupport
import com.xiaoian.app.device.DeviceSupport.Level
import com.xiaoian.app.device.DeviceSupport.Status

private val Amber = Color(0xFFE0A030)

/** The first-launch notice: the verdict and the checks behind it, nothing more. */
@Composable
fun DeviceSupportDialog(report: DeviceSupport.Report, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Device support") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Verdict(report)
                Spacer(Modifier.height(12.dp))
                Text(
                    "The info button on the dashboard shows this again, with the display " +
                        "settings each mode needs and the list of supported devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** The info button's screen: the verdict, the display settings, and the device list. */
@Composable
fun DeviceInfoScreen(innerPadding: PaddingValues = PaddingValues(0.dp)) {
    val context = LocalContext.current
    val report = remember { DeviceSupport.evaluate() }
    val settings = remember { DeviceSupport.displaySettings(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Device support", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Verdict(report)

        Section("Settings for extend and mirror")
        Text(
            "Starting a desktop in extend or mirror mode sets these developer options and asks " +
                "for a reboot the first time. Extend and mirror need different values, so switching " +
                "between them needs a reboot too. Local mode needs none of them.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        SettingsTable(settings)

        Section("Devices")
        Bullet("Tested", DeviceSupport.TESTED_DESCRIPTION)
        Bullet("Expected to work", "arm64 phones with a Snapdragon (Adreno) GPU, Android 11+, rooted with Magisk.")
        Bullet(
            "Limited",
            "Mali, Xclipse, MediaTek and Tensor (Pixel) GPUs: XFCE only with software rendering, no KDE. " +
                "Kernels before 5.11: the virtual lock cannot disable the touchscreen.",
        )
        Bullet("Untested", "Samsung (DeX), KernelSU and APatch.")
        Bullet("Not supported", "32-bit or x86 devices, phones without root.")
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Verdict(report: DeviceSupport.Report) {
    val (headline, color) = when (report.level) {
        Level.TESTED -> "Supported: this is the tested device" to MaterialTheme.colorScheme.primary
        Level.LIKELY -> "Likely supported" to MaterialTheme.colorScheme.primary
        Level.LIMITED -> "Partly supported" to Amber
        Level.UNSUPPORTED -> "Not supported" to MaterialTheme.colorScheme.error
    }
    Text(headline, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    report.checks.forEach { CheckRow(it) }
}

@Composable
private fun CheckRow(check: DeviceSupport.Check) {
    val (icon, tint) = when (check.status) {
        Status.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        Status.WARN -> Icons.Default.Warning to Amber
        Status.FAIL -> Icons.Default.Info to MaterialTheme.colorScheme.error
    }
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(check.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsTable(settings: List<DeviceSupport.DisplaySetting>) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    // Unset reads as null; the scripts treat that as 0, and so does this.
    val now = settings.map { it.current?.takeIf { v -> v != "null" } ?: "0" }

    Row {
        Text("setting", style = mono, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("now", style = mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
        Text("ext", style = mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
        Text("mir", style = mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
    }
    settings.forEachIndexed { i, s ->
        Row(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(s.key, style = mono, modifier = Modifier.weight(1f))
            Text(now[i], style = mono, modifier = Modifier.width(34.dp))
            Text(s.extend, style = mono, modifier = Modifier.width(34.dp))
            Text(s.mirror, style = mono, modifier = Modifier.width(34.dp))
        }
    }
    val summary = when (now) {
        settings.map { it.extend } -> "The current values are set for extend mode."
        settings.map { it.mirror } -> "The current values are set for mirror mode."
        else -> "The current values fit neither mode; the first extend or mirror start sets them."
    }
    Spacer(Modifier.height(8.dp))
    Text(summary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Bullet(label: String, text: String) {
    Text(
        "• $label: $text",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}
