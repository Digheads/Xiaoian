package com.xiaoian.app.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootChecker {
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
}
