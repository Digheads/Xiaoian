package com.xiaoian.app.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * One display the desktop could be sent to.
 *
 * [id] is the framework's logical display id, which is the same number the
 * scripts hand to `am start --display` and the same one `dumpsys display`
 * prints as `displayId=`.
 */
data class ExternalDisplay(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
) {
    /** "HDMI Screen — 1920×1080", for the picker. */
    val label: String get() = if (width > 0 && height > 0) "$name — $width×$height" else name

    /** What the scripts want: `WxH`, portrait-normalised by them, not here. */
    val size: String get() = "${width}x$height"
}

/**
 * Finds the displays a desktop can be put on.
 *
 * The scripts can do this themselves by parsing `dumpsys display`, and still
 * do when nothing is passed in. Asking the framework is better for two
 * reasons: it gives a name worth showing in a list, and the two `dumpsys`
 * greps disagree with each other -- `detect_external_display_id` matches only
 * `type=EXTERNAL` viewports while `detect_external_res` also accepts `VIRTUAL`
 * and `WIFI`, so a Miracast sink had its resolution found but not its id.
 */
class DisplayDetector(context: Context) {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /**
     * Every display except the phone's own, in the framework's order.
     *
     * No filtering by flags or by name: anything that is not the built-in
     * screen is something `am start --display` can target, including the
     * overlay displays the developer options can fake. A display that has gone
     * away is dropped -- [DisplayManager.getDisplays] can still list one for a
     * moment after it is unplugged.
     */
    fun externalDisplays(): List<ExternalDisplay> =
        displayManager.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
            .map { display ->
                val mode = display.mode
                ExternalDisplay(
                    id = display.displayId,
                    name = display.name.ifBlank { "Display ${display.displayId}" },
                    width = mode.physicalWidth,
                    height = mode.physicalHeight,
                )
            }

    /**
     * Calls [onChanged] whenever a display is plugged in, unplugged or
     * reconfigured. Returns a handle to stop listening with.
     */
    fun observe(onChanged: () -> Unit): AutoCloseable {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onChanged()
            override fun onDisplayRemoved(displayId: Int) = onChanged()
            override fun onDisplayChanged(displayId: Int) = onChanged()
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        return AutoCloseable { displayManager.unregisterDisplayListener(listener) }
    }
}
