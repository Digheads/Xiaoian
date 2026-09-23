# `:terminal`

The terminal emulator and view, vendored from
[termux/termux-app](https://github.com/termux/termux-app) commit
`084d709fbf23ea83b5cb85fd3d795c775be06676` (`terminal-emulator` and
`terminal-view`). Licence: see [LICENSE.md](LICENSE.md) -- GPLv3, not
Apache-2.0.

Vendored verbatim:

| upstream | here |
|---|---|
| `terminal-emulator/src/main/java/com/termux/terminal/` | `src/main/java/com/termux/terminal/` (18 files) |
| `terminal-view/src/main/java/com/termux/view/` | `src/main/java/com/termux/view/` (8 files, incl. `support/` and `textselection/`) |
| `terminal-view/src/main/res/values/strings.xml` | `src/main/res/values/strings.xml` |
| `terminal-view/src/main/res/drawable/text_select_handle_{left,right}_material.xml` | `src/main/res/drawable/` |
| `terminal-emulator/src/main/jni/termux.c` | `src/main/jni/termux.c` |

Upstream's unit tests (`terminal-emulator/src/test/`) are not vendored. The
only external dependency is `androidx.annotation`; there are no
`com.termux.shared` references to strip.

## Divergences from upstream

Kept deliberately small, so that re-syncing with upstream stays a diff.

1. **One module, namespace `com.termux.view`.** Upstream has two modules with
   namespaces `com.termux.emulator` and `com.termux.view`. This project sets
   `android.nonTransitiveRClass=true`, under which a library's generated R
   class is `<namespace>.R`, and `textselection/TextSelectionCursorController`
   and `TextSelectionHandleView` both do `import com.termux.view.R`. So the
   merged module has to be namespaced `com.termux.view`. Nothing under
   `com.termux.terminal` touches resources, which is what makes merging safe.
2. **No `-Werror`** in the native build. Upstream compiles `termux.c` with it
   under ndk-build; here it builds clean under `-Wall -Wextra` anyway, but a
   future NDK bump should not fail the build over vendored code.

3. **`TerminalSession` can adopt an existing pty.** It grew a `PtyStarter`
   hook: when one is set, `initializeEmulator()` takes the master fd and the
   pid from it instead of calling `JNI.createSubprocess()`. Everything Xiaoian
   runs in a terminal is root, and `createSubprocess()` forks with the app's
   uid, so the upstream path would mean exec'ing `su` once per session. The
   change is marked `XIAOIAN` in the file and is confined to five places.
   Because the process is then not the app's child, two things follow inside
   `TerminalSession`: `waitpid()` cannot report its exit, so the waiter thread
   is not started and the end of the session is taken from EOF on the pty
   master; and `Os.kill()` cannot reach it, so `finishIfRunning()` delegates to
   the starter. With no starter set the class behaves exactly as upstream.
4. **`XiaoianPty`** (`src/main/java/com/termux/terminal/XiaoianPty.java`) is a
   new file in the vendored package -- it needs the package-private `JNI`.
   `openPty()` does what `createSubprocess()` does up to the fork and stops
   there.

## Native code

Gradle builds these from `src/main/jni` (`CMakeLists.txt`, wired up in
`build.gradle`):

- **`libtermux.so`** -- the JNI library behind `com.termux.terminal.JNI`, from
  the vendored `termux.c` and our `pty_open.c`. Nothing checks that every
  declared `native` method has a `Java_…` function: a library silently missing
  a declared symbol is how `libanland_consumer.so` once crashed the app.
- **`libptyspawn.so`** -- not upstream. A ~40 line executable that attaches a
  command to an existing pty slave as its controlling terminal, so the root
  side of a session can be started from the one already-open `RootShell`
  instead of exec'ing another `su`. Its header comment has the full rationale.

There is no `externalNativeBuild`; run the script by hand after changing
anything under `src/main/jni`.
