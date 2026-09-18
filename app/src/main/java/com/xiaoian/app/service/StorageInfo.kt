package com.xiaoian.app.service

data class StorageInfo(
    val rootfsSize: Long = 0L,
    val installerSize: Long = 0L,
    val installed: Boolean = false
) {
    val totalSize: Long get() = rootfsSize + installerSize

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val idx = digitGroups.coerceAtMost(units.size - 1)
            return "%.1f %s".format(bytes / Math.pow(1024.0, idx.toDouble()), units[idx])
        }
    }
}
