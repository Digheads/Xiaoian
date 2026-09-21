package com.xiaoian.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.AlertDialog
import android.graphics.Paint
import android.os.Bundle
import android.text.InputFilter
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.anland.termux.ExtraKeysBar
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.xiaoian.app.R
import com.xiaoian.app.SettingsActivity
import com.xiaoian.app.service.BootstrapManager
import com.xiaoian.app.settings.AppPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * The terminal screen.
 *
 * Sessions live in [TerminalSessions], not here: this Activity is only a view
 * onto them, so rotating the phone or leaving the screen does not disturb a
 * running shell. It attaches one session at a time to a single [TerminalView]
 * and switches between them from the tab strip.
 */
class TerminalActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TerminalActivity"

        /** Which desktop's chroot to enter. Absent means the local tool rootfs. */
        const val EXTRA_DE = "com.xiaoian.app.terminal.DE"

        /** False when the caller had no environment in mind; see [intent]. */
        private const val EXTRA_HAS_SPEC = "com.xiaoian.app.terminal.HAS_SPEC"

        /** Matches the desktop bar; see anland's MainActivity.buildExtraKeysBar. */
        private const val BAR_ROW_DP = 37.5f

        /** Long enough for a cold chroot start; the spinner never outlives it. */
        private const val FIRST_OUTPUT_TIMEOUT_MS = 10_000L

        /**
         * [spec] null means "just show the terminal": whatever is already open,
         * or the chooser if nothing is.
         */
        fun intent(context: Context, spec: SessionSpec?): Intent =
            Intent(context, TerminalActivity::class.java).apply {
                if (spec is SessionSpec.DesktopChroot) putExtra(EXTRA_DE, spec.de)
                if (spec != null) putExtra(EXTRA_HAS_SPEC, true)
            }

        /** What can be opened, in the order the chooser lists them. */
        private val CHOICES = listOf(
            SessionSpec.Local,
            SessionSpec.AndroidShell,
            SessionSpec.DesktopChroot("xfce"),
            SessionSpec.DesktopChroot("kde"),
        )
    }

    private lateinit var root: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysHost: FrameLayout

    private var extraKeysBar: ExtraKeysBar? = null
    private var current: XiaoianSession? = null
    private var textSize = AppPrefs.TEXT_SIZE_DEFAULT

    /** The layout the bar on screen was built from; see [onResume]. */
    private var appliedLayoutJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        root = findViewById(R.id.terminal_root)
        tabs = findViewById(R.id.tabs)
        terminalView = findViewById(R.id.terminal_view)
        extraKeysHost = findViewById(R.id.extra_keys)

        // targetSdk 35 means the window is edge to edge whether we ask for it
        // or not, so the content has to keep clear of the bars itself.
        // windowSoftInputMode=adjustResize alone does not do it: without this
        // the key bar ends up underneath the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }

        buildExtraKeysBar()
        textSize = AppPrefs.terminalTextSize(this)

        // This order is not optional. setTextSize() builds the renderer and
        // then calls updateSize(), which divides by the renderer's metrics, and
        // attachSession() starts emulation -- doing either before the client is
        // set is an immediate NPE.
        terminalView.setTerminalViewClient(ViewClient())
        terminalView.setTextSize(textSize)

        lifecycleScope.launch {
            TerminalSessions.sessions.collect { onSessionsChanged(it) }
        }

        applyIntent(intent)
    }

    /**
     * singleTask, so a second TERMINAL tap arrives here rather than in a new
     * Activity -- without this, tapping the KDE card while the terminal was
     * already open just raised the window and ignored the request.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    /**
     * Asked for a specific environment: reuse its session if one is open,
     * otherwise start it. Asked for nothing: show whatever is open, and only
     * ask when there is nothing to show.
     */
    private fun applyIntent(intent: Intent) {
        val wanted = if (!intent.getBooleanExtra(EXTRA_HAS_SPEC, false)) null
        else intent.getStringExtra(EXTRA_DE)
            ?.let { SessionSpec.DesktopChroot(it) }
            ?: SessionSpec.Local

        val open = TerminalSessions.sessions.value
        val existing = if (wanted == null) open.lastOrNull() else open.lastOrNull { it.spec == wanted }
        when {
            existing != null -> select(existing)
            wanted != null -> openSession(wanted)
            else -> chooseSession()
        }
    }

    override fun onResume() {
        super.onResume()
        TerminalSessions.listener = sessionListener

        // Coming back from the settings screen, the bar's layout and whether it
        // is shown at all can both have changed. Compare before rebuilding, the
        // way anland's MainActivity.onResume does, so an ordinary return to the
        // terminal is not a relayout.
        val wantBar = AppPrefs.terminalExtraKeysVisible(this)
        if (AppPrefs.extraKeysLayout(this) != appliedLayoutJson || wantBar != (extraKeysBar != null)) {
            buildExtraKeysBar()
        }
        val size = AppPrefs.terminalTextSize(this)
        if (size != textSize) {
            textSize = size
            terminalView.setTextSize(size)
        }

        current?.let { terminalView.onScreenUpdated() }
        terminalView.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        // Anything longer than this and the store holds the Activity alive.
        TerminalSessions.listener = null
    }

    override fun onStop() {
        super.onStop()
        // The selection handles are PopupWindows; leaving them up while the
        // Activity goes away is a WindowLeaked.
        terminalView.stopTextSelectionMode()
    }

    override fun onContextMenuClosed(menu: Menu) {
        // The view keeps the selected text around for the "More" menu and has
        // to be told when that menu is gone.
        terminalView.onContextMenuClosed(menu)
        super.onContextMenuClosed(menu)
    }

    // ------------------------------------------------------------- sessions

    /**
     * Asks which environment to open. The "+" tab used to repeat whatever the
     * screen was opened with, which left no way to get a second kind.
     */
    private fun chooseSession() {
        val labels = CHOICES.map { spec ->
            when (spec) {
                // The tab shows only the short label; the chooser says what
                // each one is, since two of them are Debian and one is not.
                is SessionSpec.Local -> "Xiaoian \u2013 Debian, no desktop needed"
                is SessionSpec.AndroidShell -> "Android \u2013 root shell"
                is SessionSpec.DesktopChroot ->
                    if (spec.de == "kde") "KDE \u2013 desktop chroot" else "XFCE \u2013 desktop chroot"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("New terminal session")
            .setItems(labels) { _, which -> openSession(CHOICES[which]) }
            .setNegativeButton("Cancel") { _, _ ->
                // Nothing open and nothing chosen: there is no screen to show.
                if (TerminalSessions.sessions.value.isEmpty()) finish()
            }
            .setOnCancelListener {
                if (TerminalSessions.sessions.value.isEmpty()) finish()
            }
            .show()
    }

    /**
     * Shows a spinner while [block] runs.
     *
     * Opening a session waits on a root shell and closing one waits until the
     * processes are actually gone; both take a couple of seconds, during which
     * the screen looked frozen. Modal on purpose -- a second tap on "+" while
     * the first was still working opened a session nobody asked for.
     */
    private suspend fun <T> withBusy(message: String, block: suspend () -> T): T {
        val density = resources.displayMetrics.density
        val pad = Math.round(24 * density)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(
                ProgressBar(this@TerminalActivity).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(Math.round(32 * density), Math.round(32 * density)),
            )
            addView(
                TextView(this@TerminalActivity).apply { text = message },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = pad },
            )
        }
        val dialog = AlertDialog.Builder(this).setView(box).setCancelable(false).create()
        runCatching { dialog.show() }
        return try {
            block()
        } finally {
            runCatching { dialog.dismiss() }
        }
    }

    private fun openSession(spec: SessionSpec) {
        lifecycleScope.launch {
            if (spec is SessionSpec.Local && !ensureToolRootfs()) return@launch
            // The spinner stays until the shell has printed something: the
            // session starts once its view is attached, and until the first
            // output arrives the tab is just an empty black screen.
            val result = withBusy("Starting ${spec.label}...") {
                TerminalSessions.open(this@TerminalActivity, spec).onSuccess { session ->
                    val ready = CompletableDeferred<Unit>()
                    awaitingOutput = session.terminal to ready
                    select(session)
                    withTimeoutOrNull(FIRST_OUTPUT_TIMEOUT_MS) { ready.await() }
                    awaitingOutput = null
                }
            }
            result.onFailure {
                Toast.makeText(
                    this@TerminalActivity,
                    "Could not start a terminal: ${it.message}",
                    Toast.LENGTH_LONG,
                ).show()
                Log.e(TAG, "open failed", it)
                if (TerminalSessions.sessions.value.isEmpty()) finish()
            }
        }
    }

    /**
     * Makes sure the local tool rootfs exists, installing it if it does not.
     *
     * It used to be installed only on the way to starting a desktop, so a local
     * terminal on a fresh install opened, printed "not installed yet" and died
     * -- telling the user to start a desktop they may not want. It is a couple
     * of hundred megabytes to download and unpack, so it gets a progress
     * dialog rather than a spinner.
     *
     * @return false if it is still not there afterwards, with the reason shown.
     */
    private suspend fun ensureToolRootfs(): Boolean {
        val bootstrap = BootstrapManager(applicationContext)
        if (withContext(Dispatchers.IO) { bootstrap.isInstalled() }) return true

        val status = TextView(this)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
        }
        val pad = Math.round(20 * resources.displayMetrics.density)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Setting up the Debian bootstrap environment")
            .setView(box)
            // No cancel button: the work happens inside a blocking root shell
            // command, so a cancel could not actually stop it and would only
            // look like it had.
            .setCancelable(false)
            .create()
        dialog.show()

        val ok = try {
            val installed = bootstrap.installBootstrap { message, progress ->
                runOnUiThread {
                    status.text = message
                    bar.progress = (progress * 1000).toInt().coerceIn(0, 1000)
                }
            }
            if (installed) {
                // Not fatal: the shell works without them, they are just the
                // tools most likely to be wanted first.
                bootstrap.installPackages(listOf("wget", "tar", "xz-utils")) { message, progress ->
                    runOnUiThread {
                        status.text = message
                        bar.progress = (progress * 1000).toInt().coerceIn(0, 1000)
                    }
                }
            }
            installed
        } finally {
            dialog.dismiss()
        }

        if (!ok) {
            Toast.makeText(this, "Could not install the bootstrap environment", Toast.LENGTH_LONG).show()
            if (TerminalSessions.sessions.value.isEmpty()) finish()
        }
        return ok
    }

    /** The session [openSession] is waiting on for its first output. */
    private var awaitingOutput: Pair<TerminalSession, CompletableDeferred<Unit>>? = null

    private fun select(session: XiaoianSession) {
        current = session
        terminalView.attachSession(session.terminal)
        terminalView.requestFocus()
        renderTabs(TerminalSessions.sessions.value)
    }

    private fun closeSession(session: XiaoianSession) {
        // No follow-up here: the store drops it from the list, and
        // onSessionsChanged picks the next tab or closes the screen. Doing it
        // in both places meant two code paths deciding what to show next.
        lifecycleScope.launch {
            withBusy("Closing ${session.title}...") {
                TerminalSessions.close(this@TerminalActivity, session)
            }
        }
    }

    // ----------------------------------------------------------------- tabs

    /**
     * The store can drop the session this screen is showing without going
     * through [closeSession] -- stopping a desktop closes every terminal in its
     * chroot, and so does an uninstall or a rootfs reinstall. Without this the
     * tab disappeared but the view stayed attached to the dead session, showing
     * output nobody could type into.
     */
    private fun onSessionsChanged(sessions: List<XiaoianSession>) {
        val showing = current
        if (showing != null && sessions.none { it === showing }) {
            val next = sessions.lastOrNull()
            if (next == null) {
                finish()
                return
            }
            select(next)
            return
        }
        renderTabs(sessions)
    }

    private fun renderTabs(sessions: List<XiaoianSession>) {
        tabs.removeAllViews()
        sessions.forEach { session ->
            // A finished session keeps its tab so its last words stay readable;
            // the name is struck through rather than suffixed, which said the
            // same thing but pushed every other tab off the strip.
            tabs.addView(
                tabButton(session.title, session === current, session.isRunning) { select(session) }
                    .also {
                        it.setOnLongClickListener { tabMenu(session); true }
                        if (!session.isRunning) it.contentDescription = "${session.title}, ended"
                    }
            )
        }
        tabs.addView(tabButton("+", false, true) { chooseSession() })
        tabs.contentDescription = "Long press a tab for its menu"
    }

    /** Long press on a tab. Closing used to happen here with no confirmation. */
    private fun tabMenu(session: XiaoianSession) {
        val actions = arrayOf("New session", "Rename", "Close")
        AlertDialog.Builder(this)
            .setTitle(session.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> chooseSession()
                    1 -> renameDialog(session)
                    2 -> closeSession(session)
                }
            }
            .show()
    }

    private fun renameDialog(session: XiaoianSession) {
        val input = EditText(this).apply {
            setText(session.title)
            setSelection(text.length)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(TerminalSessions.MAX_NAME_LENGTH))
        }
        val pad = Math.round(20 * resources.displayMetrics.density)
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Rename session")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                // Blank restores the default name; see TerminalSessions.rename.
                TerminalSessions.rename(session, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun tabButton(
        text: String,
        active: Boolean,
        running: Boolean,
        onClick: () -> Unit,
    ): Button =
        Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
            setText(text)
            isAllCaps = false
            paintFlags =
                if (running) paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                else paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            setTextColor(
                when {
                    !running -> 0xFF777777.toInt()
                    active -> 0xFF80DEEA.toInt()
                    else -> 0xFFBBBBBB.toInt()
                }
            )
            setBackgroundColor(if (active) 0xFF202020.toInt() else 0x00000000)
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------ extra keys

    private fun buildExtraKeysBar() {
        extraKeysHost.removeAllViews()
        extraKeysBar = null
        // Recorded even when the bar is hidden, so onResume still notices a
        // layout edit made while it was off.
        appliedLayoutJson = AppPrefs.extraKeysLayout(this)

        if (!AppPrefs.terminalExtraKeysVisible(this)) {
            extraKeysHost.visibility = View.GONE
            return
        }

        val sender = ExtraKeysSender(
            terminalView,
            onToggleKeyboard = { toggleSoftKeyboard() },
            // Our own settings, not the KDE frontend's: that screen is about
            // daemon sockets and display resolution, and the one thing on it
            // that means anything here -- this bar's layout -- now lives with
            // the terminal's own options.
            onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        val bar = ExtraKeysBar(this, sender)
        extraKeysBar = bar
        val height = Math.round(BAR_ROW_DP * resources.displayMetrics.density * bar.rowCount)
        extraKeysHost.visibility = View.VISIBLE
        extraKeysHost.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    private fun toggleSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        terminalView.requestFocus()
        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    // --------------------------------------------------------------- clients

    /** Forwards the store's callbacks to this screen while it is visible. */
    private val sessionListener = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (current?.terminal === changedSession) terminalView.onScreenUpdated()
            awaitingOutput?.let { (s, ready) -> if (s === changedSession) ready.complete(Unit) }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            renderTabs(TerminalSessions.sessions.value)
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            renderTabs(TerminalSessions.sessions.value)
            awaitingOutput?.let { (s, ready) -> if (s === finishedSession) ready.complete(Unit) }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@TerminalActivity)
            if (!text.isNullOrEmpty()) current?.terminal?.write(text.toString())
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            if (current?.terminal === session) terminalView.onScreenUpdated()
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message ?: "", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }
    }

    /** Input and gestures on the terminal view itself. */
    private inner class ViewClient : TerminalViewClient {

        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                val delta = if (scale < 0.9f) -1 else 1
                textSize = (textSize + delta).coerceIn(AppPrefs.TEXT_SIZE_MIN, AppPrefs.TEXT_SIZE_MAX)
                terminalView.setTextSize(textSize)
                AppPrefs.setTerminalTextSize(this@TerminalActivity, textSize)
            }
            return 1.0f
        }

        override fun onSingleTapUp(e: MotionEvent?) = toggleSoftKeyboard()

        override fun shouldBackButtonBeMappedToEscape() = false

        /**
         * Hides what the IME thinks it knows about the text, so predictive
         * keyboards stop trying to correct shell commands.
         */
        override fun shouldEnforceCharBasedInput() = true

        override fun shouldUseCtrlSpaceWorkaround() = false

        override fun isTerminalViewSelected() = true

        override fun copyModeChanged(copyMode: Boolean) = Unit

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            // "[Process completed - press Enter]" is a promise the emulator
            // makes on our behalf; this is what keeps it.
            val showing = current
            if (keyCode == KeyEvent.KEYCODE_ENTER && showing != null && !showing.isRunning) {
                closeSession(showing)
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false

        override fun onLongPress(event: MotionEvent?) = false

        // The bar's own keys carry their modifiers with them; these are for a
        // character coming from the soft keyboard while a bar modifier is up.
        override fun readControlKey() = extraKeysBar?.isModifierActive("CTRL") == true
        override fun readAltKey() = extraKeysBar?.isModifierActive("ALT") == true
        override fun readShiftKey() = extraKeysBar?.isModifierActive("SHIFT") == true
        override fun readFnKey() = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            // The character has taken the modifiers, so release the ones that
            // were only tapped; a locked one stays lit, as on the desktop.
            extraKeysBar?.consumeUnlockedModifiers()
            return false
        }

        override fun onEmulatorSet() = Unit

        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message ?: "", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }
    }
}
