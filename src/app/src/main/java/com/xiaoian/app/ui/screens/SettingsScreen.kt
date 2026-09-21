package com.xiaoian.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anland.termux.ExtraKeysBar
import com.xiaoian.app.settings.AppPrefs

/**
 * The app's settings.
 *
 * This screen owns what is genuinely ours or genuinely shared, and links to
 * the two frontends for the rest. It deliberately does **not** merge their two
 * preference screens: `:lorie` drives androidx.preference from an XML file that
 * a Gradle task turns into a typed `Prefs` class, `:anland` hand-builds
 * framework views over a store of its own, and both modules get overwritten
 * wholesale on the next upstream sync (`termux-x11-update.md`,
 * `anland-update.md`). A merged screen would become a manual merge every time.
 *
 * What was worth sharing is the key bar: the terminal draws the same
 * [ExtraKeysBar] as the KDE desktop, from the same stored layout, and until now
 * the only way to edit it was the KDE frontend's settings -- a screen about
 * daemon sockets, microphones and display resolution.
 */
@Composable
fun SettingsScreen(innerPadding: PaddingValues = PaddingValues(0.dp)) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        ExtraKeysSection()

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        TerminalSection()

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))


        Text("Desktop frontends", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Each desktop is rendered by its own frontend, with settings of its own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        LinkRow(
            title = "KDE (Wayland) frontend",
            summary = "Display, touchpad, audio, camera and the display daemon.",
        ) { open(context, "com.anland.termux.SettingsActivity") }

        Spacer(Modifier.height(8.dp))

        LinkRow(
            title = "XFCE (X11) frontend",
            summary = "Resolution, scaling and filtering, pointer and scancode handling, " +
                "and its own extra keys bar.",
        ) { open(context, "com.termux.x11.LoriePreferences") }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The key bar layout, shared with the KDE desktop.
 *
 * The editor is prefilled with the built-in default when nothing is stored, but
 * that prefill is *not* written back -- see [AppPrefs.extraKeysLayout]. Only an
 * actual edit persists, and only when it parses: anland's own editor saves every
 * keystroke, which drops the bar to the default mid-word.
 */
@Composable
private fun ExtraKeysSection() {
    val context = LocalContext.current

    var layout by remember {
        mutableStateOf(AppPrefs.extraKeysLayout(context).ifEmpty { ExtraKeysBar.defaultLayoutJson() })
    }

    // validateLayout() returns null when the JSON parses; the string it returns
    // otherwise is meant to be shown as is. Blank is not an error -- it is how
    // the user asks for the built-in layout back.
    val blank = layout.isBlank()
    val problem = ExtraKeysBar.validateLayout(layout).takeUnless { blank }

    val apply: (String) -> Unit = { text ->
        layout = text
        if (text.isBlank() || ExtraKeysBar.validateLayout(text) == null) {
            AppPrefs.setExtraKeysLayout(context, text)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }.onSuccess(apply).onFailure {
            Toast.makeText(context, "Could not read that file", Toast.LENGTH_SHORT).show()
        }
    }

    Text("Extra keys bar", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "The key row above the keyboard, in the terminal and on the KDE desktop -- both " +
            "read this layout. XFCE has a bar of its own, in a different format; it is " +
            "edited in the XFCE frontend's settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = layout,
        onValueChange = apply,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Layout (JSON)") },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        minLines = 6,
        maxLines = 16,
        isError = problem != null,
        supportingText = {
            when {
                problem != null -> Text(problem)
                blank -> Text("Empty -- the built-in layout is used.")
                else -> Text("Saved.")
            }
        },
    )

    Spacer(Modifier.height(8.dp))

    Row {
        OutlinedButton(onClick = { apply(ExtraKeysBar.defaultLayoutJson()) }) {
            Text("Load default")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain")) }) {
            Text("Load from file")
        }
    }
}

@Composable
private fun TerminalSection() {
    val context = LocalContext.current

    var textSize by remember { mutableStateOf(AppPrefs.terminalTextSize(context)) }
    var showBar by remember { mutableStateOf(AppPrefs.terminalExtraKeysVisible(context)) }

    Text("Terminal", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))

    Text("Font size: $textSize", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = textSize.toFloat(),
        onValueChange = { textSize = it.toInt() },
        // Written once the gesture ends: the terminal reads this on resume, and
        // every intermediate value on the way to 22 is not worth a write.
        onValueChangeFinished = { AppPrefs.setTerminalTextSize(context, textSize) },
        valueRange = AppPrefs.TEXT_SIZE_MIN.toFloat()..AppPrefs.TEXT_SIZE_MAX.toFloat(),
        steps = AppPrefs.TEXT_SIZE_MAX - AppPrefs.TEXT_SIZE_MIN - 1,
    )
    Text(
        "Pinch to zoom in the terminal changes this too.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Show extra keys bar", style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = showBar,
            onCheckedChange = {
                showBar = it
                AppPrefs.setTerminalExtraKeysVisible(context, it)
            },
        )
    }
}

@Composable
private fun LinkRow(title: String, summary: String, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * Both targets are merged in from library manifests rather than declared here,
 * so they are started by name -- and wrapped, because a module dropped from the
 * build should not crash the settings screen.
 */
private fun open(context: android.content.Context, className: String) {
    runCatching {
        context.startActivity(Intent().setClassName(context.packageName, className))
    }.onFailure {
        Toast.makeText(context, "That frontend is not available", Toast.LENGTH_SHORT).show()
    }
}
