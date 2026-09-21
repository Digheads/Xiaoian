package com.xiaoian.app

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.xiaoian.app.input.DesktopInput
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
 * the soft keyboard with a row of special keys above it; touching the
 * touchpad puts both away again. Closes itself once the session is no longer
 * a running, unlocked extend session: the lock turns touch off, and in the
 * other modes the desktop is on this very screen.
 */
class TouchpadActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_DE = "de"
        private const val ACCENT = 0xFF80DEEA.toInt()

        fun start(context: Context, de: String) {
            val intent = Intent(context, TouchpadActivity::class.java)
                .putExtra(EXTRA_DE, de)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // The phone's screen, explicitly: with the desktop focused on the
            // external display, a plain start could land over there.
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            context.startActivity(intent, options.toBundle())
        }
    }

    private lateinit var input: DesktopInput
    private lateinit var capture: InputCaptureView
    private lateinit var bar: HorizontalScrollView
    private lateinit var fab: TextView
    private var keyboardOpen = false
    private val modKeys = mutableMapOf<DesktopInput.Mod, TextView>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val de = intent.getStringExtra(EXTRA_DE) ?: run { finish(); return }
        input = DesktopInput(de)
        if (!input.available()) {
            Toast.makeText(this, "Open the desktop first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF111111.toInt()) }
        root.addView(TouchpadView(this, input) { closeKeyboardIfOpen() }, FrameLayout.LayoutParams(-1, -1))

        capture = InputCaptureView(this, input)
        root.addView(capture, FrameLayout.LayoutParams(1, 1))

        bar = buildKeysBar()
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))

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
        root.addView(fab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            keyboardOpen = insets.isVisible(WindowInsetsCompat.Type.ime())
            // The keys sit right on top of the keyboard, and only while it is up.
            bar.visibility = if (keyboardOpen) View.VISIBLE else View.GONE
            (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin = ime
            (fab.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = (if (keyboardOpen) ime + dp(48) else nav) + dp(16)
                marginEnd = dp(16)
            }
            bar.requestLayout()
            fab.requestLayout()
            insets
        }
        setContentView(root)

        lifecycleScope.launch {
            SessionManagerProvider.sessionManager.state.collect { state ->
                val ok = state is SessionState.Running && state.mode == "extend" &&
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

    // ---- special keys ------------------------------------------------------

    private sealed class Key(val label: String) {
        class Plain(label: String, val code: Int) : Key(label)
        class Modifier(val mod: DesktopInput.Mod) : Key(mod.label)
    }

    private val keys = listOf(
        Key.Plain("ESC", KeyEvent.KEYCODE_ESCAPE),
        Key.Plain("TAB", KeyEvent.KEYCODE_TAB),
        Key.Modifier(DesktopInput.Mod.CTRL),
        Key.Modifier(DesktopInput.Mod.ALT),
        Key.Modifier(DesktopInput.Mod.SUPER),
        Key.Modifier(DesktopInput.Mod.SHIFT),
        Key.Plain("←", KeyEvent.KEYCODE_DPAD_LEFT),
        Key.Plain("↑", KeyEvent.KEYCODE_DPAD_UP),
        Key.Plain("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        Key.Plain("→", KeyEvent.KEYCODE_DPAD_RIGHT),
        Key.Plain("HOME", KeyEvent.KEYCODE_MOVE_HOME),
        Key.Plain("END", KeyEvent.KEYCODE_MOVE_END),
        Key.Plain("PGUP", KeyEvent.KEYCODE_PAGE_UP),
        Key.Plain("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
        Key.Plain("DEL", KeyEvent.KEYCODE_FORWARD_DEL),
    )

    private val repeatHandler = Handler(Looper.getMainLooper())

    @SuppressLint("ClickableViewAccessibility")
    private fun buildKeysBar(): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (key in keys) {
            val view = TextView(this).apply {
                text = key.label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(14), 0, dp(14), 0)
                minWidth = dp(48)
            }
            when (key) {
                is Key.Modifier -> {
                    modKeys[key.mod] = view
                    // Tap: the next key only. Long press: until turned off.
                    view.setOnClickListener { input.toggleMod(key.mod, lock = false) }
                    view.setOnLongClickListener { input.toggleMod(key.mod, lock = true); true }
                }
                is Key.Plain -> view.setOnTouchListener { v, e ->
                    // Held keys repeat, like a real keyboard's arrows.
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            input.key(key.code)
                            val repeat = object : Runnable {
                                override fun run() {
                                    input.key(key.code)
                                    repeatHandler.postDelayed(this, 60)
                                }
                            }
                            repeatHandler.postDelayed(repeat, 400)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            repeatHandler.removeCallbacksAndMessages(null)
                        }
                    }
                    true
                }
            }
            row.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, -1))
        }
        input.onModsChanged = { refreshMods() }
        return HorizontalScrollView(this).apply {
            setBackgroundColor(0xEE222222.toInt())
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(row, FrameLayout.LayoutParams(-2, -1))
        }
    }

    private fun refreshMods() {
        for ((mod, view) in modKeys) {
            when (input.modState(mod)) {
                DesktopInput.ModState.OFF -> { view.setTextColor(Color.WHITE); view.setBackgroundColor(Color.TRANSPARENT) }
                DesktopInput.ModState.ONCE -> { view.setTextColor(ACCENT); view.setBackgroundColor(Color.TRANSPARENT) }
                DesktopInput.ModState.LOCKED -> { view.setTextColor(Color.BLACK); view.setBackgroundColor(ACCENT) }
            }
        }
    }

    override fun onDestroy() {
        repeatHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
