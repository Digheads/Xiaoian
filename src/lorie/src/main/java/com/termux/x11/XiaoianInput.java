package com.termux.x11;

import com.termux.x11.input.InputStub;

import java.nio.charset.StandardCharsets;

/**
 * Xiaoian: input for the running X server from outside this module -- the
 * app's phone-screen touchpad, while the desktop itself is on an external
 * display. MainActivity is an AppCompat activity the app cannot compile
 * against, so this plain class is the whole surface it uses.
 *
 * Main thread only, like the activity it talks through.
 */
public final class XiaoianInput {
    private XiaoianInput() {}

    private static LorieView view() {
        MainActivity activity = MainActivity.getInstance();
        if (activity == null)
            return null;
        LorieView view = activity.getLorieView();
        return view != null && view.connected() ? view : null;
    }

    public static boolean available() { return view() != null; }

    /** Relative pointer motion, in desktop pixels. */
    public static void move(float dx, float dy) {
        LorieView v = view();
        if (v != null) v.sendMouseEvent(dx, dy, InputStub.BUTTON_UNDEFINED, false, true);
    }

    /** 1 = left, 2 = middle, 3 = right. */
    public static void button(int button, boolean down) {
        LorieView v = view();
        if (v != null) v.sendMouseEvent(0, 0, button, down, true);
    }

    /** Finger travel in pixels, GestureDetector-style: positive y scrolls down. */
    public static void scroll(float dx, float dy) {
        LorieView v = view();
        if (v != null) v.sendMouseWheelEvent(dx, dy);
    }

    /** An Android key code; the X server has its own mapping for these. */
    public static void key(int keyCode, boolean down) {
        LorieView v = view();
        if (v != null) v.sendKeyEvent(0, keyCode, down);
    }

    public static void text(String s) {
        LorieView v = view();
        if (v != null && !s.isEmpty()) v.sendTextEvent(s.getBytes(StandardCharsets.UTF_8));
    }
}
