package com.xiaoian.app.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

class DisplayDetector(context: Context) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun findExternalDisplay(): Display? {
        return displayManager.displays.firstOrNull { display ->
            display.displayId != Display.DEFAULT_DISPLAY &&
            (display.flags and Display.FLAG_PRESENTATION != 0 || display.name.contains("external", true))
        }
    }

    fun getExternalDisplays(): List<Display> {
        return displayManager.displays.filter { display ->
            display.displayId != Display.DEFAULT_DISPLAY &&
            (display.flags and Display.FLAG_PRESENTATION != 0 || display.name.contains("external", true))
        }
    }
}
