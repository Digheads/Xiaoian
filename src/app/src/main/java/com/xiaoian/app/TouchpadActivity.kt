package com.xiaoian.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.anland.termux.ExtraKeysBar
import com.xiaoian.app.input.DesktopInput
import com.xiaoian.app.model.Desktop
import com.xiaoian.app.model.DisplayMode
import com.xiaoian.app.input.InputCaptureView
import com.xiaoian.app.input.TouchpadView
import com.xiaoian.app.service.SessionManagerProvider
import com.xiaoian.app.service.SessionState
import kotlinx.coroutines.launch

/**
 * The phone as touchpad and keyboard for a desktop in extend mode, where the
 * desktop is on the external display and the phone's own screen is free.
 *
 * The whole screen is the touchpad. The button at the bottom right brings up
 * the soft keyboard with the terminal's special-keys bar (same layout
 * setting) above it; touching the
 * touchpad puts both away again. Closes itself once the session is no longer
 * a running, unlocked extend session: the lock turns touch off, and in the
 * other modes the desktop is on this very screen.
 */
class TouchpadActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_DE = "de"
        private const val ACCENT = 0xFF80DEEA.toInt()
        /** Same row height as under the terminal. */
        private const val BAR_ROW_DP = 37.5f

        fun start(context: Context, de: Desktop) {
            val intent = Intent(context, TouchpadActivity::class.java)
                .putExtra(EXTRA_DE, de.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // The phone's screen, explicitly: with the desktop focused on the
            // external display, a plain start could land over there.
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            context.startActivity(intent, options.toBundle())
        }
    }

    private lateinit var input: DesktopInput
    private lateinit var capture: InputCaptureView
    private lateinit var bar: ExtraKeysBar
    private lateinit var fab: TextView
    private var keyboardOpen = false
    private var barHeight = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val de = intent.getStringExtra(EXTRA_DE)?.let(Desktop::fromId) ?: run { finish(); return }
        input = DesktopInput(de)
        if (!input.available()) {
            Toast.makeText(this, "Open the desktop first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // No grey contrast scrim behind the gesture bar: the touchpad runs to
        // the bottom edge, and the keyboard button's gaps read as equal.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            window.isNavigationBarContrastEnforced = false
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF111111.toInt()) }
        root.addView(TouchpadView(this, input) { closeKeyboardIfOpen() }, FrameLayout.LayoutParams(-1, -1))

        capture = InputCaptureView(this, input)
        root.addView(capture, FrameLayout.LayoutParams(1, 1))

        // The terminal's special-keys bar, with the same layout setting.
        input.onToggleKeyboard = { if (keyboardOpen) hideKeyboard() else showKeyboard() }
        input.onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        bar = ExtraKeysBar(this, input).apply { visibility = View.GONE }
        input.bar = bar
        barHeight = Math.round(BAR_ROW_DP * resources.displayMetrics.density * bar.rowCount)
        root.addView(bar, FrameLayout.LayoutParams(-1, barHeight, Gravity.BOTTOM))

        fab = TextView(this).apply {
            text = "⌨"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
            elevation = dp(6).toFloat()
            contentDescription = "Keyboard"
            setOnClickListener { if (keyboardOpen) hideKeyboard() else showKeyboard() }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.RIGHT).apply {
            bottomMargin = dp(16); rightMargin = dp(16)
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            keyboardOpen = insets.isVisible(WindowInsetsCompat.Type.ime())
            // The keys sit right on top of the keyboard, and only while it is up.
            bar.visibility = if (keyboardOpen) View.VISIBLE else View.GONE
            bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = ime }
            if (!keyboardOpen) bar.reset()
            // The same gap from the right edge as from the bottom one.
            val gap = dp(16) + bars.bottom
            fab.layoutParams = (fab.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = if (keyboardOpen) ime + barHeight + dp(16) else gap
                rightMargin = gap + bars.right
            }
            insets
        }
        setContentView(root)

        lifecycleScope.launch {
            SessionManagerProvider.sessionManager.state.collect { state ->
                val ok = state is SessionState.Running && state.mode == DisplayMode.EXTEND &&
                    !state.isLocked && state.de == de
                if (!ok) finish()
            }
        }
    }

    private fun showKeyboard() {
        capture.requestFocus()
        WindowInsetsControllerCompat(window, capture).show(WindowInsetsCompat.Type.ime())
    }

    private fun hideKeyboard() {
        WindowInsetsControllerCompat(window, capture).hide(WindowInsetsCompat.Type.ime())
    }

    /** For the touchpad: a touch while typing only puts the keyboard away. */
    private fun closeKeyboardIfOpen(): Boolean {
        if (!keyboardOpen) return false
        hideKeyboard()
        return true
    }

    override fun onDestroy() {
        input.bar?.reset()
        super.onDestroy()
    }
}
