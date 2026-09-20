# 03 — Terminal Emulator Integration

## Overview

The Xiaoian app needs an embedded terminal emulator that supports two session types:
1. **Local shell** — bash in the app's own `$PREFIX` environment
2. **Chroot shell** — bash inside the Debian chroot (requires root + running session)

## Source: Termux Terminal Libraries

Termux's terminal implementation consists of two separate libraries:

### 1. `terminal-emulator` (Java library)
- **Repo:** https://github.com/termux/termux-app/tree/master/terminal-emulator
- **License:** GPLv3-only — see [src/terminal/LICENSE.md](../src/terminal/LICENSE.md)
- **What it does:** Pure Java VT100/xterm terminal state machine
  - Parses escape sequences
  - Maintains a character grid (rows × columns)
  - Handles scrollback buffer
  - No rendering — just terminal state

### 2. `terminal-view` (Android View)
- **Repo:** https://github.com/termux/termux-app/tree/master/terminal-view
- **License:** GPLv3-only — see [src/terminal/LICENSE.md](../src/terminal/LICENSE.md)
- **What it does:** Android `View` subclass that renders the terminal state
  - Text rendering with Canvas
  - Touch input (scroll, select, paste)
  - Keyboard input handling
  - Cursor blinking, text selection, clipboard

> **Correction (2026-09-20, when the libraries were actually vendored):** the
> claim that these libraries are Apache-2.0 was wrong. Neither directory has a
> `LICENSE` file and no copied source file has a licence header; the termux-app
> root `LICENSE.md` says the repository is **GPLv3-only** and that these two
> libraries *contain* Apache-2.0 code from Terminal Emulator for Android — which
> is not the same as relicensing them. Treat the vendored code as GPLv3. It
> changes nothing in practice, since the APK is already GPL because of the
> Termux:X11 fork in `:lorie`. Full reasoning in
> [src/terminal/LICENSE.md](../src/terminal/LICENSE.md).

## Architecture

```
┌─ TerminalActivity ─────────────────────────────────────────┐
│                                                             │
│  ┌─ TabBar ──────────────────────────────────────────────┐ │
│  │ [Local #1]  [Chroot #1]  [Chroot #2]  [+]            │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ TerminalView (terminal-view fork) ───────────────────┐ │
│  │                                                        │ │
│  │  ┌─ TerminalEmulator (terminal-emulator fork) ──────┐ │ │
│  │  │  • VT100/xterm-256color state machine             │ │ │
│  │  │  • Character grid (rows × cols)                   │ │ │
│  │  │  • Scrollback buffer (10000 lines)                │ │ │
│  │  │  • Color palette (256 colors + true color)        │ │ │
│  │  └──────────────────────────────────────────────────┘ │ │
│  │                                                        │ │
│  │  Canvas rendering:                                     │ │
│  │  • Monospace font (JetBrains Mono / Fira Code)        │ │
│  │  • Anti-aliased text                                   │ │
│  │  • Selection highlighting                              │ │
│  │  • Cursor (block/underline/bar)                        │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ ExtraKeysBar ────────────────────────────────────────┐ │
│  │ [ESC] [CTRL] [ALT] [TAB] [←] [→] [↑] [↓] [|] [~]    │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Session Types

### Local Shell

```kotlin
class LocalShellSession(private val prefix: String) : TerminalSession {

    override fun createProcess(): Process {
        val env = mapOf(
            "PREFIX" to prefix,
            "HOME" to "$prefix/../home",
            "PATH" to "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin",
            "TMPDIR" to "$prefix/tmp",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
        )

        return ProcessBuilder("$prefix/bin/bash", "-l")
            .directory(File("$prefix/../home"))
            .environment().putAll(env)
            .redirectErrorStream(true)
            .start()
    }
}
```

### Chroot Shell

```kotlin
class ChrootShellSession(
    private val infraRoot: String  // e.g. /data/local/xiaoian-wayland-kde
) : TerminalSession {

    private val debianRoot = "$infraRoot/debian"

    override fun createProcess(): Process {
        // Verify chroot is set up and mounted
        require(File("$debianRoot/bin/bash").exists()) {
            "Debian rootfs not found. Start the desktop first."
        }

        val command = arrayOf(
            "su", "-c",
            """chroot "$debianRoot" /usr/bin/env -i \
                HOME=/root \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                TERM=xterm-256color \
                LANG=en_US.UTF-8 \
                /bin/bash -l"""
        )

        return Runtime.getRuntime().exec(command)
    }
}
```

## PTY (Pseudo-Terminal) Handling

Android's `Process` class doesn't allocate a PTY by default. For proper terminal behavior (line editing, signal handling, job control, ncurses), we need a real PTY.

### Option A: JNI PTY (recommended)

```c
// native-lib.c
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <termios.h>

JNIEXPORT jint JNICALL
Java_com_xiaoian_app_terminal_JNI_createPty(
    JNIEnv *env, jclass cls,
    jstring cmd, jobjectArray args, jobjectArray envVars,
    jint rows, jint cols) {

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    grantpt(ptm);
    unlockpt(ptm);

    char* pts_name = ptsname(ptm);
    pid_t pid = fork();

    if (pid == 0) {
        // Child: open slave PTY, set as controlling terminal
        setsid();
        int pts = open(pts_name, O_RDWR);
        ioctl(pts, TIOCSCTTY, 0);

        dup2(pts, STDIN_FILENO);
        dup2(pts, STDOUT_FILENO);
        dup2(pts, STDERR_FILENO);
        close(pts);
        close(ptm);

        struct winsize ws = { .ws_row = rows, .ws_col = cols };
        ioctl(STDIN_FILENO, TIOCSWINSZ, &ws);

        // Set environment, exec shell
        // ...
        execvp(cmd_str, argv);
        _exit(1);
    }

    return ptm;  // Return master fd to Java
}
```

### Option B: Use Termux's `terminal-emulator` library

The `terminal-emulator` library already includes JNI code for PTY creation (`JNI.java` → `termux-jni.c`). Forking this library gives us PTY handling for free.

## Extra Keys Bar

A floating toolbar above the soft keyboard for keys that Android keyboards don't provide:

```
┌──────────────────────────────────────────────────────────────┐
│ ESC │ CTRL │ ALT │ TAB │  ←  │  ↑  │  ↓  │  →  │  ~  │  /  │
└──────────────────────────────────────────────────────────────┘
```

Implementation: A horizontal `RecyclerView` or `Row` (Compose) with key buttons that inject keycodes into the terminal emulator.

## Multiple Sessions

```kotlin
class TerminalSessionManager {
    private val sessions = mutableListOf<TerminalSession>()
    private var activeSession: Int = 0

    fun newLocalSession(): Int { /* ... */ }
    fun newChrootSession(): Int { /* ... */ }
    fun switchTo(index: Int) { /* ... */ }
    fun closeSession(index: Int) { /* ... */ }
}
```

Each session has its own `TerminalEmulator` instance and subprocess. The `TerminalView` switches between them by swapping the backing `TerminalEmulator`.

## Visual Differentiation

| Session Type | Color Scheme | Prompt | Tab Color |
|---|---|---|---|
| Local | Dark (Dracula-like) | `xiaoian $` | Blue |
| Chroot | Dark with green tint | `root@debian:~#` | Green |
| Root (su) | Dark with red tint | `phone:/ #` | Red |

## Hardware Keyboard Support

When an external keyboard is connected (USB or Bluetooth):
- The extra keys bar is hidden
- Full key mapping (Ctrl+C, Ctrl+Z, Ctrl+D, function keys, etc.)
- The `TerminalView` handles `onKeyDown`/`onKeyUp` directly

## Estimated Effort

| Component | Time |
|---|---|
| Fork and integrate `terminal-emulator` + `terminal-view` | 3–4 days |
| PTY JNI setup | 2–3 days |
| Session management (local + chroot) | 2–3 days |
| Extra keys bar | 1–2 days |
| Multiple sessions / tabs | 2–3 days |
| Polish (fonts, colors, scrollback, selection) | 2–3 days |
| **Total** | **~2–3 weeks** |
