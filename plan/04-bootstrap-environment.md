# 04 — Bootstrap Environment

## Overview

To eliminate the Termux dependency, the Xiaoian app must provide its own Termux-compatible Linux environment. This document describes how to bootstrap a `$PREFIX` directory with all required tools and libraries.

## What is the Termux Bootstrap?

When Termux is first installed, it downloads a **bootstrap tarball** (~30–50 MB) containing a minimal Linux environment cross-compiled for Android:

- `bash`, `coreutils`, `grep`, `sed`, `awk`
- `apt`, `dpkg` (package management)
- `openssl`, `ca-certificates` (HTTPS)
- `libandroid-support`, `libc++` and other shared libraries
- Directory structure: `bin/`, `lib/`, `etc/`, `var/`, `share/`, `tmp/`

The official bootstrap tarballs are hosted at:
```
https://packages.termux.dev/bootstrap/bootstrap-aarch64.zip
```

## Bootstrap Flow

```
App first launch
    │
    ▼
┌─ BootstrapManager.ensureReady() ─────────────────────────────────┐
│                                                                    │
│  1. Check: does $PREFIX/bin/bash exist?                           │
│     ├─ YES → return (already bootstrapped)                        │
│     └─ NO  → continue                                             │
│                                                                    │
│  2. Show progress UI: "Setting up environment..."                 │
│                                                                    │
│  3. Download bootstrap tarball                                     │
│     ├─ Primary: bundled in APK assets/ (~50 MB, offline-first)    │
│     └─ Fallback: download from packages.termux.dev                │
│                                                                    │
│  4. Extract to /data/data/com.xiaoian.app/files/usr/              │
│     ├─ Progress: file count / total                                │
│     └─ Atomic: extract to .tmp/, rename to usr/ on success        │
│                                                                    │
│  5. Fix symlinks                                                   │
│     └─ Bootstrap uses SYMLINKS.txt for hardlink→symlink restore   │
│                                                                    │
│  6. Configure APT                                                  │
│     ├─ Write sources.list (Termux package repos)                  │
│     └─ Set architecture (aarch64)                                  │
│                                                                    │
│  7. Install required packages                                      │
│     ├─ apt update                                                  │
│     ├─ apt install wget tar anland termux-x11-nightly pulseaudio  │
│     └─ Progress: package name + download %                         │
│                                                                    │
│  8. Validate                                                       │
│     ├─ Check: bash, wget, tar, anland, termux-x11 all present    │
│     └─ Mark bootstrap as complete                                  │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

## Directory Structure

```
/data/data/com.xiaoian.app/
├── files/
│   ├── usr/                          ← $PREFIX (Termux-compatible)
│   │   ├── bin/
│   │   │   ├── bash
│   │   │   ├── wget
│   │   │   ├── tar
│   │   │   ├── apt
│   │   │   ├── dpkg
│   │   │   ├── anland              ← installed via apt
│   │   │   ├── termux-x11          ← installed via apt
│   │   │   └── pulseaudio          ← installed via apt
│   │   ├── lib/
│   │   │   ├── libc++_shared.so
│   │   │   ├── libandroid-support.so
│   │   │   └── ...
│   │   ├── etc/
│   │   │   ├── apt/
│   │   │   │   └── sources.list
│   │   │   ├── profile
│   │   │   └── bash.bashrc
│   │   ├── var/
│   │   │   ├── lib/dpkg/           ← package database
│   │   │   └── cache/apt/          ← downloaded .debs
│   │   ├── share/
│   │   └── tmp/                    ← $TMPDIR, runtime sockets
│   └── home/                       ← $HOME
│       └── .bashrc
└── cache/                          ← APK cache (bootstrap tarball)
```

## Environment Variables

```kotlin
object XiaoianEnvironment {
    val FILES_DIR = context.filesDir.absolutePath
    val PREFIX = "$FILES_DIR/usr"
    val HOME = "$FILES_DIR/home"
    val TMPDIR = "$PREFIX/tmp"
    val PATH = "$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin"
    val LD_LIBRARY_PATH = "$PREFIX/lib"

    fun asMap(): Map<String, String> = mapOf(
        "PREFIX" to PREFIX,
        "HOME" to HOME,
        "TMPDIR" to TMPDIR,
        "PATH" to PATH,
        "LD_LIBRARY_PATH" to LD_LIBRARY_PATH,
        "XDG_RUNTIME_DIR" to TMPDIR,
        "ANDROID_ROOT" to "/system",
        "ANDROID_DATA" to "/data",
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8",
    )
}
```

## Shell Script $PREFIX Adaptation

The existing shell scripts hardcode Termux's `$PREFIX`:

```bash
# Current (in both scripts):
export PREFIX="/data/data/com.termux/files/usr"
```

This needs to become dynamic:

```bash
# New: detect whether running from Xiaoian app or standalone Termux
if [ -n "$XIAOIAN_PREFIX" ]; then
    export PREFIX="$XIAOIAN_PREFIX"
elif [ -d "/data/data/com.xiaoian.app/files/usr/bin" ]; then
    export PREFIX="/data/data/com.xiaoian.app/files/usr"
else
    export PREFIX="/data/data/com.termux/files/usr"
fi
```

The app passes `XIAOIAN_PREFIX` as an environment variable when invoking scripts:

```kotlin
shellExecutor.run(
    command = "su -c 'XIAOIAN_PREFIX=$prefix ./xiaoian-wayland-kde.sh -s --extend'",
)
```

## APK Size Considerations

| Approach | APK Size | First Run | Offline? |
|---|---|---|---|
| **Bundle bootstrap in APK** | ~80–100 MB | Fast (extract only) | ✅ Yes |
| **Download on first run** | ~15 MB | Slow (50 MB download) | ❌ No |
| **Hybrid** (APK + download extras) | ~20 MB | Medium | Partially |

### Recommended: Hybrid approach

1. Bundle a **minimal bootstrap** in APK `assets/` (~15 MB): bash, coreutils, apt, dpkg, wget, tar, ca-certificates
2. On first run: extract minimal bootstrap, then `apt install` the rest (anland, termux-x11, pulseaudio)
3. This keeps the APK reasonable (~20 MB) while minimizing first-run downloads

## Package Installation

```kotlin
class PackageManager(private val prefix: String) {

    private val requiredPackages = listOf(
        "anland",                    // Wayland display daemon
        "termux-x11-nightly",        // X11 display server
        "pulseaudio",                // Audio (XFCE variant)
    )

    suspend fun installMissing(onProgress: (String) -> Unit) {
        // 1. Update package index
        onProgress("Updating package index...")
        shellExec("$prefix/bin/apt update")

        // 2. Check which packages are missing
        val missing = requiredPackages.filter { !isInstalled(it) }

        // 3. Install missing packages
        for (pkg in missing) {
            onProgress("Installing $pkg...")
            shellExec("$prefix/bin/apt install -y $pkg")
        }
    }

    private fun isInstalled(pkg: String): Boolean {
        return shellExec("$prefix/bin/dpkg -s $pkg").exitCode == 0
    }
}
```

## Bootstrap Updates

The bootstrap only needs updating when:
- Termux releases a new bootstrap version (rare, ~2–3 times/year)
- The app itself updates and requires new packages

Update strategy:
1. Check bootstrap version marker file: `$PREFIX/.xiaoian-bootstrap-version`
2. If version < app's expected version → download and apply delta, or re-bootstrap
3. Package updates: `apt update && apt upgrade` (user-triggered, not automatic)

## Error Recovery

| Failure | Recovery |
|---|---|
| Bootstrap download interrupted | Delete `.tmp/`, retry from scratch |
| Extraction fails (disk full) | Show disk space warning, clean up partial extraction |
| APT install fails | Show error log, offer retry button |
| Corrupt $PREFIX | "Reset environment" button → wipe $PREFIX, re-bootstrap |

## Estimated Effort

| Component | Time |
|---|---|
| BootstrapManager (download, extract, validate) | 4–5 days |
| Environment setup (vars, paths, permissions) | 2–3 days |
| APT configuration and package installation | 3–4 days |
| Shell script $PREFIX adaptation | 2–3 days |
| First-run UI (progress, errors) | 2–3 days |
| Error recovery and edge cases | 3–4 days |
| **Total** | **~3–4 weeks** |
