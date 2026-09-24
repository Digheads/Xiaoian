package com.xiaoian.app.model

/**
 * One of the two desktop environments the app can run, carrying everything
 * that differs between them in one place so no call site re-derives it from
 * the id string.
 */
enum class Desktop(
    /** The id used in intents, preferences and the shell scripts. */
    val id: String,
    /** `/data/local/...` root the script and chroot live under. */
    val infraRoot: String,
    /** File name of the start script, as bundled in the APK assets. */
    val scriptName: String,
    /**
     * Further APK assets the script expects beside itself in [infraRoot].
     * KDE's is Anland's Plasma start helper, pinned to the version the
     * vendored Anland frontend was taken from (see anland-update.md) instead
     * of being fetched from upstream's main branch at run time.
     */
    val extraAssets: List<String> = emptyList(),
) {
    KDE("kde", "/data/local/xiaoian-wayland-kde", "xiaoian-wayland-kde.sh",
        listOf("startplasma-anland.sh")),
    XFCE("xfce", "/data/local/xiaoian-x11-xfce", "xiaoian-x11-xfce.sh");

    /** Short uppercase label, e.g. "KDE". */
    val label: String get() = id.uppercase()

    /** The name shown on the dashboard, e.g. "KDE (Wayland)". */
    val displayName: String
        get() = when (this) {
            KDE -> "KDE (Wayland)"
            XFCE -> "XFCE (X11)"
        }

    /** Absolute path the script is installed to and run from. */
    val scriptPath: String get() = "$infraRoot/$scriptName"

    companion object {
        val DEFAULT = KDE
        fun fromId(id: String?): Desktop = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}
