package com.xiaoian.app.settings

import android.content.Context

/**
 * The preferences the app itself owns, plus the one it shares with the KDE
 * frontend.
 *
 * Three stores exist in this APK and they belong to different owners:
 *
 *  - `xiaoian` -- ours. The terminal's own settings live here.
 *  - `anland_settings` -- the `:anland` module's. We touch exactly one key in
 *    it, [extraKeysLayout], because the terminal and the KDE desktop draw the
 *    same [com.anland.termux.ExtraKeysBar] from it.
 *  - the default store plus `secondary` -- the `:lorie` module's, driven by its
 *    own generated `Prefs` class. We never touch those.
 *
 * Both vendored modules are re-synced from upstream (see `anland-update.md`
 * and `termux-x11-update.md`), so the one borrowed name is pinned here rather
 * than spelled out at each call site: if an update ever renames it, this is the
 * single place to fix, and the constant below says where to look.
 */
object AppPrefs {

    /** Our own store. Also used by `service.BootReceiver`. */
    private const val XIAOIAN_PREFS = "xiaoian"

    /** Owned by `:anland`; see `ExtraKeysBar.PREFS_NAME` / `KEY_EXTRA_KEYS_LAYOUT`. */
    private const val ANLAND_PREFS = "anland_settings"
    private const val KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout"

    private const val KEY_TERMINAL_TEXT_SIZE = "terminal_text_size"
    private const val KEY_TERMINAL_EXTRA_KEYS = "terminal_extra_keys"

    const val TEXT_SIZE_MIN = 8
    const val TEXT_SIZE_MAX = 36
    const val TEXT_SIZE_DEFAULT = 14

    private fun ours(context: Context) =
        context.getSharedPreferences(XIAOIAN_PREFS, Context.MODE_PRIVATE)

    private fun anland(context: Context) =
        context.getSharedPreferences(ANLAND_PREFS, Context.MODE_PRIVATE)

    /**
     * The extra keys bar layout, as JSON.
     *
     * Empty is a valid, meaningful value: the bar falls back to its built-in
     * layout, so clearing the editor is how the user resets it. Storing a copy
     * of the default instead would pin them to today's default forever.
     */
    fun extraKeysLayout(context: Context): String =
        anland(context).getString(KEY_EXTRA_KEYS_LAYOUT, "").orEmpty()

    fun setExtraKeysLayout(context: Context, json: String) {
        anland(context).edit().putString(KEY_EXTRA_KEYS_LAYOUT, json).apply()
    }

    fun terminalTextSize(context: Context): Int =
        ours(context).getInt(KEY_TERMINAL_TEXT_SIZE, TEXT_SIZE_DEFAULT)
            .coerceIn(TEXT_SIZE_MIN, TEXT_SIZE_MAX)

    fun setTerminalTextSize(context: Context, size: Int) {
        ours(context).edit()
            .putInt(KEY_TERMINAL_TEXT_SIZE, size.coerceIn(TEXT_SIZE_MIN, TEXT_SIZE_MAX))
            .apply()
    }

    /**
     * Whether the terminal shows the extra keys bar. Separate from anland's
     * `extra_keys_bar`, which governs the bar on the KDE desktop -- the same
     * bar, but a different screen wants it at different times.
     */
    fun terminalExtraKeysVisible(context: Context): Boolean =
        ours(context).getBoolean(KEY_TERMINAL_EXTRA_KEYS, true)

    fun setTerminalExtraKeysVisible(context: Context, visible: Boolean) {
        ours(context).edit().putBoolean(KEY_TERMINAL_EXTRA_KEYS, visible).apply()
    }
}
