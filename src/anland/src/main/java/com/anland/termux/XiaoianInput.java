package com.anland.termux;

import java.nio.charset.StandardCharsets;

/**
 * Xiaoian: input for the running compositor from outside this module -- the
 * app's phone-screen touchpad, while the desktop itself is on an external
 * display. Same surface as com.termux.x11.XiaoianInput, so the app can pick
 * either one by desktop.
 *
 * Main thread only, like the activity it talks through.
 */
public final class XiaoianInput {
    private XiaoianInput() {}

    // linux/input-event-codes.h
    private static final int BTN_LEFT = 0x110, BTN_RIGHT = 0x111, BTN_MIDDLE = 0x112;

    private static MainActivity activity() {
        MainActivity a = MainActivity.sInstance;
        return a != null && a.isSurfaceReady() ? a : null;
    }

    public static boolean available() { return activity() != null; }

    /** Relative pointer motion, in desktop pixels. */
    public static void move(float dx, float dy) {
        MainActivity a = activity();
        if (a != null) a.injectRelativeMotion(dx, dy);
    }

    /** 1 = left, 2 = middle, 3 = right. */
    public static void button(int button, boolean down) {
        if (activity() == null) return;
        int code = button == 3 ? BTN_RIGHT : button == 2 ? BTN_MIDDLE : BTN_LEFT;
        Native.nativeSendMouseButton(code, down);
    }

    /**
     * Finger travel in pixels, GestureDetector-style: positive y scrolls down.
     * Anland's wheel notch is 10 units where Termux:X11's is 100.
     */
    public static void scroll(float dx, float dy) {
        if (activity() == null) return;
        if (dy != 0f) Native.nativeSendMouseScroll(0, dy / 10f);
        if (dx != 0f) Native.nativeSendMouseScroll(1, dx / 10f);
    }

    /** An Android key code, sent as its evdev scan code. */
    public static void key(int keyCode, boolean down) {
        if (activity() == null) return;
        // Page Up/Down are missing from KeyCodeMapper.
        int evdev = keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP ? 104
            : keyCode == android.view.KeyEvent.KEYCODE_PAGE_DOWN ? 109
            : KeyCodeMapper.getScanCode(keyCode);
        if (evdev > 0) Native.nativeSendKey(down ? 0 : 1, evdev);
    }

    /** An evdev scan code, as the extra-keys bar sends them. */
    public static void keyEvdev(int evdev, boolean down) {
        if (activity() != null && evdev > 0) Native.nativeSendKey(down ? 0 : 1, evdev);
    }

    public static void text(String s) {
        if (activity() == null || s.isEmpty()) return;
        Native.nativeSendTextInput(s.getBytes(StandardCharsets.UTF_8));
    }
}
