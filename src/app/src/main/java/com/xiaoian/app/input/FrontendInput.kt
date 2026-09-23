package com.xiaoian.app.input

import com.xiaoian.app.model.Desktop

/**
 * The static input surface the two vendored display frontends expose, behind
 * one interface so [DesktopInput] never has to branch on which desktop it is.
 *
 * The frontends' `XiaoianInput` classes are AppCompat activity companions the
 * app cannot compile against directly, so each implementation calls its own
 * module's static methods; the two differ only in how they send a key (see
 * [Evdev]).
 */
interface FrontendInput {
    fun available(): Boolean
    fun move(dx: Float, dy: Float)
    fun button(button: Int, down: Boolean)
    fun scroll(dx: Float, dy: Float)
    fun text(s: String)
    /** A key by evdev scan code. The X11 frontend wants Android key codes, so it translates. */
    fun keyEvdev(evdev: Int, down: Boolean)

    companion object {
        fun forDesktop(de: Desktop): FrontendInput = when (de) {
            Desktop.KDE -> AnlandFrontendInput()
            Desktop.XFCE -> LorieFrontendInput()
        }
    }
}

/** The Anland (KDE/Wayland) frontend. */
private class AnlandFrontendInput : FrontendInput {
    override fun available() = com.anland.termux.XiaoianInput.available()
    override fun move(dx: Float, dy: Float) = com.anland.termux.XiaoianInput.move(dx, dy)
    override fun button(button: Int, down: Boolean) = com.anland.termux.XiaoianInput.button(button, down)
    override fun scroll(dx: Float, dy: Float) = com.anland.termux.XiaoianInput.scroll(dx, dy)
    override fun text(s: String) = com.anland.termux.XiaoianInput.text(s)
    override fun keyEvdev(evdev: Int, down: Boolean) = com.anland.termux.XiaoianInput.keyEvdev(evdev, down)
}

/** The Termux:X11 (XFCE/X11) frontend. */
private class LorieFrontendInput : FrontendInput {
    override fun available() = com.termux.x11.XiaoianInput.available()
    override fun move(dx: Float, dy: Float) = com.termux.x11.XiaoianInput.move(dx, dy)
    override fun button(button: Int, down: Boolean) = com.termux.x11.XiaoianInput.button(button, down)
    override fun scroll(dx: Float, dy: Float) = com.termux.x11.XiaoianInput.scroll(dx, dy)
    override fun text(s: String) = com.termux.x11.XiaoianInput.text(s)
    override fun keyEvdev(evdev: Int, down: Boolean) {
        val keyCode = Evdev.toKeyCode(evdev)
        if (keyCode >= 0) com.termux.x11.XiaoianInput.key(keyCode, down)
    }
}
