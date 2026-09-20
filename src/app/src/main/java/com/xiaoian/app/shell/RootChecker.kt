package com.xiaoian.app.shell

/**
 * Whether the app has root.
 *
 * This runs `id` through the shared [RootShell] rather than spawning its own
 * `su`: on a cold start it is the command that opens that shell, so the
 * permission prompt it triggers is the same one everything else then reuses.
 */
class RootChecker(private val shell: RootShell = RootShell.shared) {
    suspend fun isRootAvailable(): Boolean = shell.exec("id -u", idleTimeoutMs = 20_000).success
}
