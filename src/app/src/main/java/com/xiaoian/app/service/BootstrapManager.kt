package com.xiaoian.app.service

import android.content.Context
import android.util.Log
import com.xiaoian.app.shell.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class BootstrapManager(private val context: Context) {
    companion object {
        private const val TAG = "BootstrapManager"
        private const val INDEX_URL = "https://images.linuxcontainers.org/streams/v1/images.json"
        const val TARBALL_NAME = "rootfs-arm64.tar.xz"
        /** Anything smaller than this is a truncated or failed download. */
        private const val MIN_TARBALL_BYTES = 10_000_000L
    }

    val prefixDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * Downloaded Debian tarball. It lives under filesDir, not cacheDir: the
     * desktop scripts extract their own chroot from this same file, and
     * Android may evict cacheDir at any time -- which left the scripts
     * failing with "run the app setup first" and no way to recover.
     */
    val rootfsTarball: File
        get() = File(File(context.filesDir, "downloads"), TARBALL_NAME)

    fun isInstalled(): Boolean {
        // Files are root-owned, so Java File.exists() can't see them – use su
        val output = runSuCommandWithOutput("test -f ${prefixDir.absolutePath}/bin/bash && test -f ${prefixDir.absolutePath}/usr/bin/apt-get && echo OK")
        val installed = output.contains("OK")
        Log.d(TAG, "isInstalled check: $installed (output='$output'), prefixDir=${prefixDir.absolutePath}")
        return installed
    }

    /**
     * Makes sure [rootfsTarball] is present, downloading it if needed. Callers
     * must run this before a desktop script that installs its chroot.
     * Returns false if the download failed.
     */
    suspend fun ensureRootfsTarball(onProgress: (String, Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dest = rootfsTarball
                dest.parentFile?.mkdirs()

                if (dest.length() >= MIN_TARBALL_BYTES) {
                    Log.i(TAG, "Tarball present: ${dest.absolutePath} (${dest.length()} bytes)")
                    return@withContext true
                }

                // Reuse a tarball a previous version left in cacheDir.
                val legacy = File(context.cacheDir, TARBALL_NAME)
                if (legacy.length() >= MIN_TARBALL_BYTES) {
                    onProgress("Moving cached rootfs tarball...", 0.08f)
                    if (legacy.renameTo(dest)) {
                        Log.i(TAG, "Migrated tarball from cacheDir to ${dest.absolutePath}")
                        return@withContext true
                    }
                    Log.w(TAG, "Could not migrate cached tarball, downloading instead")
                }

                dest.delete()
                onProgress("Looking up latest Debian Trixie image...", 0.08f)
                val rootfsUrl = getLatestDebianUrl()
                Log.i(TAG, "Resolved rootfs URL: $rootfsUrl")

                onProgress("Downloading Debian rootfs (this may take a while)...", 0.10f)
                val part = File(dest.parentFile, "$TARBALL_NAME.part")
                part.delete()
                downloadFile(rootfsUrl, part) { bytesRead, contentLength ->
                    if (contentLength > 0) {
                        val pct = bytesRead.toFloat() / contentLength
                        val mbRead = bytesRead / (1024 * 1024)
                        val mbTotal = contentLength / (1024 * 1024)
                        onProgress("Downloading: ${mbRead}MB / ${mbTotal}MB", 0.10f + pct * 0.30f)
                    } else {
                        val mbRead = bytesRead / (1024 * 1024)
                        onProgress("Downloading: ${mbRead}MB...", 0.20f)
                    }
                }
                // Rename only once complete, so an interrupted download is
                // never mistaken for a usable cached tarball.
                if (part.length() < MIN_TARBALL_BYTES || !part.renameTo(dest)) {
                    Log.e(TAG, "Download incomplete (${part.length()} bytes)")
                    part.delete()
                    onProgress("ERROR: rootfs download failed", 0.0f)
                    return@withContext false
                }
                // Root reads this from the scripts; app-private dirs are 0700.
                runSuCommand("chmod 755 ${dest.parentFile?.absolutePath} && chmod 644 ${dest.absolutePath}")
                Log.i(TAG, "Download complete: ${dest.length()} bytes")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch rootfs tarball", e)
                onProgress("ERROR: ${e.message}", 0.0f)
                false
            }
        }

    suspend fun installBootstrap(onProgress: (String, Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefix = prefixDir.absolutePath

            if (prefixDir.exists()) {
                // A local terminal holds `--bind /dev` inside this tree. The
                // unmount below refuses while it is busy, and toybox `rm` has
                // no --one-file-system, so getting this wrong deletes the
                // host's device nodes.
                com.xiaoian.app.terminal.TerminalSessions.closeAllLocal(context)
                onProgress("Removing old rootfs...", 0.02f)
                Log.d(TAG, "Cleaning up old rootfs at $prefix")
                if (!wipeRootfs(prefix)) {
                    onProgress("ERROR: could not unmount the old rootfs", 0.02f)
                    return@withContext false
                }
            }

            onProgress("Creating rootfs directory...", 0.05f)
            runSuCommand("mkdir -p $prefix")

            if (!ensureRootfsTarball(onProgress)) return@withContext false
            val tarballFile = rootfsTarball

            // Android's native tar has no xz support, so decompress .tar.xz -> .tar in Java first.
            // Kept beside the tarball rather than in cacheDir: extraction takes
            // minutes, and an eviction halfway through would corrupt it.
            val plainTarFile = File(tarballFile.parentFile, "rootfs-arm64.tar")
            if (!plainTarFile.exists() || plainTarFile.length() < 1000) {
                onProgress("Decompressing xz archive...", 0.42f)
                Log.i(TAG, "Decompressing ${tarballFile.absolutePath} -> ${plainTarFile.absolutePath}")
                try {
                    XZInputStream(BufferedInputStream(FileInputStream(tarballFile))).use { xzIn ->
                        FileOutputStream(plainTarFile).use { out ->
                            val buf = ByteArray(65536)
                            var totalWritten = 0L
                            var lastReportedMb = -1L
                            var n: Int
                            while (xzIn.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                totalWritten += n
                                val mb = totalWritten / (1024 * 1024)
                                if (mb != lastReportedMb) {
                                    onProgress("Decompressing: ${mb}MB extracted...", 0.42f + (mb.toFloat() / 500f).coerceAtMost(0.15f))
                                    lastReportedMb = mb
                                }
                            }
                        }
                    }
                    Log.i(TAG, "Decompression complete: ${plainTarFile.length()} bytes")
                } catch (e: Exception) {
                    Log.e(TAG, "XZ decompression failed", e)
                    plainTarFile.delete()
                    onProgress("ERROR: XZ decompression failed: ${e.message}", 0.42f)
                    return@withContext false
                }
            } else {
                Log.i(TAG, "Using cached decompressed tar: ${plainTarFile.length()} bytes")
            }

            onProgress("Extracting rootfs (this will take a few minutes)...", 0.58f)
            Log.i(TAG, "Extracting ${plainTarFile.absolutePath} to $prefix via su")
            val extractResult = runSuCommand("tar -xf ${plainTarFile.absolutePath} -C $prefix", RootShell.NO_TIMEOUT)
            if (!extractResult) {
                Log.e(TAG, "tar extraction failed!")
                onProgress("ERROR: Extraction failed!", 0.58f)
                return@withContext false
            }
            // Clean up the intermediate .tar to save space
            plainTarFile.delete()
            Log.i(TAG, "Extraction complete")

            // Verify key files exist after extraction
            onProgress("Verifying extracted files...", 0.75f)
            val verifyResult = runSuCommand("ls -la $prefix/bin/bash $prefix/usr/bin/apt-get")
            if (!verifyResult) {
                Log.e(TAG, "Post-extraction verification failed: key files missing")
                // List what's actually in the rootfs for debugging
                val lsResult = runSuCommandWithOutput("ls $prefix/")
                Log.e(TAG, "Contents of $prefix: $lsResult")
                val lsBin = runSuCommandWithOutput("ls $prefix/bin/ 2>/dev/null | head -20")
                Log.e(TAG, "Contents of $prefix/bin/: $lsBin")
                val lsUsr = runSuCommandWithOutput("ls $prefix/usr/bin/ 2>/dev/null | head -20")
                Log.e(TAG, "Contents of $prefix/usr/bin/: $lsUsr")

                onProgress("ERROR: Rootfs verification failed – key files missing!", 0.75f)
                return@withContext false
            }

            onProgress("Configuring DNS resolution...", 0.80f)
            configureNetwork()
            Log.i(TAG, "Network configured")

            // Set correct ownership so chroot works smoothly
            onProgress("Setting permissions...", 0.90f)
            runSuCommand("chmod -R 755 $prefix/bin $prefix/usr/bin $prefix/usr/sbin 2>/dev/null")

            onProgress("Bootstrap installation complete!", 1.0f)
            Log.i(TAG, "Bootstrap installation finished successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install rootfs", e)
            onProgress("ERROR: ${e.message}", 0.0f)
            false
        }
    }

    private fun getLatestDebianUrl(): String {
        val url = URL(INDEX_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.connect()
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("Failed to fetch LXC images index (HTTP ${conn.responseCode})")
        }
        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }

        val regex = Regex("debian/trixie/arm64/default/([0-9]{8}_[0-9]{2}:[0-9]{2})")
        val matches = regex.findAll(jsonStr).map { it.groupValues[1] }.toList()

        if (matches.isEmpty()) {
            throw Exception("Failed to find Debian Trixie arm64 rootfs in LXC images index")
        }

        val latestDate = matches.sorted().last()
        Log.i(TAG, "Latest Debian Trixie date: $latestDate")
        return "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/$latestDate/rootfs.tar.xz"
    }

    private fun configureNetwork() {
        val prefix = prefixDir.absolutePath
        // Ensure /etc exists
        runSuCommand("mkdir -p $prefix/etc")
        // Append Android network GIDs for apt to work
        runSuCommand("sh -c 'echo aid_inet:x:3003:_apt >> $prefix/etc/group'")
        runSuCommand("sh -c 'echo aid_net_raw:x:3004:_apt >> $prefix/etc/group'")
        // Remove dangling systemd symlink and write a real resolv.conf
        runSuCommand("rm -f $prefix/etc/resolv.conf")
        runSuCommand("sh -c 'echo nameserver 8.8.8.8 > $prefix/etc/resolv.conf'")
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("Download failed: server returned HTTP ${conn.responseCode}")
        }

        val contentLength = conn.contentLengthLong
        Log.d(TAG, "Download content length: $contentLength bytes")

        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var lastReportedMb = -1L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val currentMb = totalRead / (1024 * 1024)
                    if (currentMb != lastReportedMb) {
                        onProgress(totalRead, contentLength)
                        lastReportedMb = currentMb
                    }
                }
            }
        }
    }

    /**
     * Deletes the tool rootfs, but never through a mount.
     *
     * The in-app terminal bind-mounts `/dev`, `/dev/pts`, `/proc` and `/sys`
     * into this tree. Android's toybox `rm` has no `--one-file-system`, so a
     * plain `rm -rf` here walks straight into the bind mount and deletes the
     * host's device nodes. Unmount innermost-first, refuse to delete while
     * anything is still mounted, and only then remove the tree.
     */
    /**
     * Unmounts everything under [prefix] and then deletes it.
     *
     * Two hazards, both measured on the device:
     *
     * 1. `rm -rf` over a live `--bind /dev` walks into the bind and deletes the
     *    host's device nodes -- toybox `rm` has no `--one-file-system`. Hence
     *    the final check, which refuses to delete while anything is still
     *    mounted under here.
     * 2. Unmounting a mount propagates the unmount to the peers of its
     *    **parent** mount. For the ordinary binds that is harmless: the parent
     *    is the `/data` mount, and the host's `/dev` is not a child of `/data`.
     *    It is not harmless for `mnt/android`, which the local session creates
     *    with `-o rbind /`: there the replica's root *is* a peer of the real
     *    `/`, so unmounting its children reaches the host's `/system`, `/data`
     *    and the rest -- the phone loses every binary mid-session and has to be
     *    rebooted. `SessionScripts` already takes that subtree out of the peer
     *    group with `rslave` when it creates it; doing it again here, on every
     *    mount, costs nothing and covers anything an older build left behind.
     *
     * Order matters too: reverse lexicographic sort puts a child mount path
     * before its parent, which is the order they have to go in.
     */
    private fun wipeRootfs(prefix: String): Boolean {
        if (!runSuCommand(com.xiaoian.app.terminal.SessionScripts.unmountTree(prefix))) {
            Log.e(TAG, "Refusing to delete $prefix: something is still mounted under it")
            return false
        }
        return runSuCommand("rm -rf $prefix", RootShell.NO_TIMEOUT)
    }

    /**
     * Runs one command in the shared root shell.
     *
     * These used to be `ProcessBuilder("su", "-c", …)` each, so installing the
     * tool rootfs alone cost a dozen Magisk prompts. The chroot `apt-get` runs
     * below can go quiet for minutes, hence the explicit idle timeouts.
     */
    private fun runSuCommand(command: String, idleTimeoutMs: Long = RootShell.DEFAULT_IDLE_TIMEOUT_MS): Boolean {
        val output = StringBuilder()
        val result = RootShell.shared.execBlocking(command, idleTimeoutMs) { line ->
            output.appendLine(line)
        }
        if (output.isNotBlank()) Log.d(TAG, "root: $output")
        if (!result.success) {
            Log.e(TAG, "root command failed (exit ${result.exitCode}): $command\n" +
                "stderr: ${result.stderr}\noutput: $output")
        }
        return result.success
    }

    private fun runSuCommandWithOutput(command: String): String {
        val output = StringBuilder()
        val result = RootShell.shared.execBlocking(command) { line -> output.appendLine(line) }
        // The callers grep this for markers, so stderr belongs in it too --
        // the old version merged the streams with redirectErrorStream.
        if (result.stderr.isNotBlank()) output.appendLine(result.stderr)
        return output.toString().trim()
    }

    suspend fun installPackages(packages: List<String>, onProgress: (String, Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext true

        val prefix = prefixDir.absolutePath

        onProgress("Updating package lists...", 0.91f)
        Log.i(TAG, "Running apt update inside chroot")

        val updateOk = runSuCommand("chroot $prefix /bin/bash -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; apt-get update'", RootShell.NO_TIMEOUT)
        if (!updateOk) {
            Log.e(TAG, "apt-get update failed")
            onProgress("ERROR: apt-get update failed!", 0.91f)
            return@withContext false
        }

        val pkgsStr = packages.joinToString(" ")
        onProgress("Installing: $pkgsStr", 0.95f)
        Log.i(TAG, "Installing packages: $pkgsStr")

        val installOk = runSuCommand("chroot $prefix /bin/bash -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; DEBIAN_FRONTEND=noninteractive apt-get install -y $pkgsStr'", RootShell.NO_TIMEOUT)
        if (!installOk) {
            Log.e(TAG, "apt-get install failed for: $pkgsStr")
            onProgress("ERROR: Package installation failed!", 0.95f)
            return@withContext false
        }

        onProgress("Packages installed successfully", 1.0f)
        true
    }
}
