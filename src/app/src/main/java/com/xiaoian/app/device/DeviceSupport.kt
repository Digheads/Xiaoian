package com.xiaoian.app.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.xiaoian.app.model.DisplayMode
import java.io.File

/**
 * What this phone is likely to run, judged without root: every check reads
 * something an ordinary app may read, so the first-run notice never costs a
 * superuser prompt. The reasons behind each rule are in ARCHITECTURE.md,
 * "Device support".
 */
object DeviceSupport {

    enum class Level { TESTED, LIKELY, LIMITED, UNSUPPORTED }
    enum class Status { OK, WARN, FAIL }

    data class Check(val title: String, val status: Status, val detail: String)

    data class Report(val level: Level, val checks: List<Check>)

    /** A global setting the desktop scripts set, and what each mode wants. */
    data class DisplaySetting(val key: String, val extend: String, val mirror: String, val current: String?)

    /** The phone everything was developed and tested on. */
    private const val TESTED_DEVICE = "sapphiren"
    const val TESTED_DESCRIPTION = "Xiaomi Redmi Note 13 (4G) — Snapdragon 685 / Adreno 610, HyperOS 2 (Android 15)"

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap",
    )

    fun evaluate(): Report {
        val checks = mutableListOf<Check>()

        val arm64 = "arm64-v8a" in Build.SUPPORTED_ABIS
        checks += Check(
            "64-bit ARM (arm64-v8a)",
            if (arm64) Status.OK else Status.FAIL,
            if (arm64) "The Debian rootfs and all native parts are arm64."
            else "This device is ${Build.SUPPORTED_ABIS.firstOrNull()}; only arm64 builds exist.",
        )

        val android11 = Build.VERSION.SDK_INT >= 30
        checks += Check(
            "Android 11 or newer",
            if (android11) Status.OK else Status.WARN,
            if (android11) "Android ${Build.VERSION.RELEASE}."
            else "Android ${Build.VERSION.RELEASE}: KDE needs Android 11; XFCE may still work.",
        )

        // Existence only. Running su here would pop the superuser prompt on
        // first launch, before the user has asked the app for anything.
        val root = SU_PATHS.any { File(it).exists() }
        checks += Check(
            "Root access",
            if (root) Status.OK else Status.FAIL,
            if (root) "A root manager was found. Tested with Magisk."
            else "No su binary found. The desktops run in a chroot and need root.",
        )

        val adreno = File("/dev/kgsl-3d0").exists() ||
            (Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true))
        checks += Check(
            "Qualcomm Adreno GPU",
            if (adreno) Status.OK else Status.WARN,
            if (adreno) "Hardware rendering through Freedreno/Turnip."
            else "No Adreno GPU: XFCE falls back to software rendering, KDE will not start.",
        )

        val kernelOk = kernelAtLeast(5, 11)
        checks += Check(
            "Kernel 5.11 or newer",
            if (kernelOk) Status.OK else Status.WARN,
            if (kernelOk) "Kernel ${kernelVersion()}."
            else "Kernel ${kernelVersion()}: the virtual lock cannot disable the touchscreen.",
        )

        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            checks += Check(
                "Samsung DeX",
                Status.WARN,
                "Not tested. DeX takes over external displays and may conflict with extend mode.",
            )
        }

        val level = when {
            checks.any { it.status == Status.FAIL } -> Level.UNSUPPORTED
            !adreno || !android11 -> Level.LIMITED
            Build.DEVICE == TESTED_DEVICE -> Level.TESTED
            else -> Level.LIKELY
        }
        return Report(level, checks)
    }

    /** Read fresh each time: they change on every mode switch that needs a reboot. */
    fun displaySettings(context: Context): List<DisplaySetting> {
        val cr = context.contentResolver
        fun read(key: String) = runCatching { Settings.Global.getString(cr, key) }.getOrNull()
        return listOf(
            DisplaySetting("enable_freeform_support", "1", "1", read("enable_freeform_support")),
            DisplaySetting("force_desktop_mode_on_external_displays", "1", "0", read("force_desktop_mode_on_external_displays")),
            DisplaySetting("enable_non_resizable_multi_window", "1", "0", read("enable_non_resizable_multi_window")),
            DisplaySetting("force_resizable_activities", "1", "0", read("force_resizable_activities")),
        )
    }

    /**
     * Which of extend or mirror the stored global settings are set up for, or
     * null for neither. Same comparison as the scripts' own check, unset
     * counting as 0.
     */
    fun configuredDisplayMode(context: Context): DisplayMode? {
        val s = displaySettings(context)
        val now = s.map { it.current?.takeIf { v -> v != "null" } ?: "0" }
        return when (now) {
            s.map { it.extend } -> DisplayMode.EXTEND
            s.map { it.mirror } -> DisplayMode.MIRROR
            else -> null
        }
    }

    private fun kernelVersion(): String = System.getProperty("os.version").orEmpty().substringBefore('-')

    private fun kernelAtLeast(major: Int, minor: Int): Boolean {
        val parts = kernelVersion().split('.').mapNotNull { it.toIntOrNull() }
        val maj = parts.getOrNull(0) ?: return false
        val min = parts.getOrNull(1) ?: 0
        return maj > major || (maj == major && min >= minor)
    }
}
