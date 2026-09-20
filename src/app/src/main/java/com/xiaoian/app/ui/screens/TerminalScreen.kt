package com.xiaoian.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoian.app.service.BootstrapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

/** Everything the local shell keeps in scrollback before the top is dropped. */
private const val SCROLLBACK_LINES = 2000

/**
 * The tool rootfs is a plain Debian tree with nothing mounted into it, and the
 * shell inherits Android's environment, whose PATH points at directories that
 * do not exist inside the chroot -- without this, every command comes back as
 * "command not found". /proc, /sys and /dev are what `ps`, `df` and anything
 * writing to /dev/null need; the mounts are idempotent and shared with any
 * later terminal, so they are set up but never torn down.
 */
private fun localShellScript(rootfs: String): String = """
    R='$rootfs'
    if [ ! -x "${'$'}R/bin/bash" ]; then
        echo 'The Debian tool environment is not installed yet.'
        echo 'Start a desktop session once -- the app installs it on the way.'
        exit 1
    fi
    for m in proc sys dev dev/pts; do mkdir -p "${'$'}R/${'$'}m"; done
    grep -q " ${'$'}R/proc " /proc/mounts || mount -t proc proc "${'$'}R/proc"
    grep -q " ${'$'}R/sys " /proc/mounts || mount -t sysfs sys "${'$'}R/sys"
    grep -q " ${'$'}R/dev " /proc/mounts || mount --bind /dev "${'$'}R/dev"
    grep -q " ${'$'}R/dev/pts " /proc/mounts || mount --bind /dev/pts "${'$'}R/dev/pts"
    exec chroot "${'$'}R" /usr/bin/env -i \
        HOME=/root \
        TERM=xterm-256color \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        LANG=C.UTF-8 \
        /bin/bash -i
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rootfs = remember { BootstrapManager(context).prefixDir.absolutePath }

    var outputLines by remember { mutableStateOf(listOf<String>()) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var process by remember { mutableStateOf<Process?>(null) }
    var writer by remember { mutableStateOf<PrintWriter?>(null) }

    LaunchedEffect(rootfs) {
        withContext(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder("su", "-c", localShellScript(rootfs))
                pb.redirectErrorStream(true)
                val p = pb.start()
                process = p
                writer = PrintWriter(OutputStreamWriter(p.outputStream), true)

                val reader = BufferedReader(InputStreamReader(p.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: ""
                    withContext(Dispatchers.Main) {
                        outputLines = (outputLines + currentLine).takeLast(SCROLLBACK_LINES)
                    }
                }
                // Reaching here means the shell is gone; typing into a dead
                // pipe would otherwise fail silently.
                withContext(Dispatchers.Main) {
                    writer = null
                    outputLines = (outputLines + "[session ended]").takeLast(SCROLLBACK_LINES)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    outputLines = outputLines + "Error starting terminal: ${e.message}"
                }
            }
        }
    }

    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            process?.destroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Terminal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color.Black)) {

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                state = listState
            ) {
                items(outputLines) { line ->
                    Text(
                        text = line,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }

            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = writer != null,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = Color.White),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color.DarkGray,
                    cursorColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val command = inputText
                        inputText = ""
                        coroutineScope.launch(Dispatchers.IO) {
                            writer?.println(command)
                        }
                    }
                ),
                placeholder = { Text("Enter command...", color = Color.Gray) }
            )
        }
    }
}
