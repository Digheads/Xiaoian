package com.xiaoian.app.terminal

/** What a terminal session is attached to. */
sealed interface SessionSpec {

    /** Shown on the session's tab. */
    val label: String

    /**
     * Identity for grouping, not for uniqueness -- several sessions can share
     * it. Teardown before a desktop stop or a rootfs wipe selects on this.
     */
    val group: String

    /**
     * The app's own small Debian tree under `filesDir/rootfs`, the one the
     * bootstrap installs. Independent of any desktop: it works with nothing
     * running.
     */
    data object Local : SessionSpec {
        override val label = "LOCAL"
        override val group = "local"
    }

    /**
     * The chroot of a *running* desktop, entered as a consumer: it never
     * mounts anything itself. The desktop script owns `CHROOT_MOUNTS` and its
     * `unmount_all()` tears down exactly that list, so a terminal that added
     * a mount of its own would make the desktop fail to stop.
     */
    data class DesktopChroot(val de: String) : SessionSpec {
        override val label = de.uppercase()
        override val group = "de:$de"
    }
}
