package com.xiaoian.app.input

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * An invisible, focusable target for the soft keyboard: everything typed is
 * forwarded to the desktop and nothing is kept here.
 *
 * A visible-password field, so keyboards skip suggestions and commit each
 * character at once. Some still compose; a composing word is mirrored on the
 * desktop by erasing the previous version and typing the new one.
 */
@SuppressLint("ViewConstructor")
class InputCaptureView(context: Context, private val input: DesktopInput) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            private fun replaceComposing(new: String) {
                repeat(composing.length) { input.typeKey(KeyEvent.KEYCODE_DEL) }
                input.typeText(new)
                composing = new
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { input.typeKey(KeyEvent.KEYCODE_DEL) }
                repeat(afterLength) { input.typeKey(KeyEvent.KEYCODE_FORWARD_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                handleKey(event)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                input.typeKey(KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) super.onKeyDown(keyCode, event)
        else { handleKey(event); true }

    /** Key events some keyboards send instead of text (Enter, Backspace, sometimes letters). */
    private fun handleKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val ch = event.unicodeChar
        when {
            event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_DEL ||
                event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL || ch == 0 -> input.typeKey(event.keyCode)
            else -> input.typeText(String(Character.toChars(ch)))
        }
    }
}
