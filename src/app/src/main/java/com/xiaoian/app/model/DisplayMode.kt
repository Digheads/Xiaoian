package com.xiaoian.app.model

/**
 * The way a desktop is shown: on an external screen (extend/mirror) or on the
 * phone itself (local). [id] is the value used in intents, preferences and on
 * the script command line.
 */
enum class DisplayMode(val id: String) {
    EXTEND("extend"),
    MIRROR("mirror"),
    LOCAL("local");

    companion object {
        val DEFAULT = EXTEND
        fun fromId(id: String?): DisplayMode = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}
