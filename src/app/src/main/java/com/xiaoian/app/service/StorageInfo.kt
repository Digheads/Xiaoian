package com.xiaoian.app.service

/**
 * How much space one environment occupies.
 *
 * A single figure: the downloaded installer assets no longer live under the
 * environment's infra root (they are shared between the two desktops, in the
 * app's own `files/downloads`), so there is nothing left to break the number
 * down into.
 */
data class StorageInfo(
    val sizeBytes: Long = 0L,
    val installed: Boolean = false
) {
    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val idx = digitGroups.coerceAtMost(units.size - 1)
            return String.format(java.util.Locale.ROOT, "%.1f %s", bytes / Math.pow(1024.0, idx.toDouble()), units[idx])
        }
    }
}
