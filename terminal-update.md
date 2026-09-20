# Terminal Update Guide (Vendored Termux Emulator)

The in-app terminal is **vendored**, not integrated the way `:lorie` and
`:anland` are: the Java sources are copied verbatim into `src/terminal/` and the
native code is compiled from the copied `termux.c`. There is no precompiled
`.so` to lift out of a release APK — Termux ships those inside the Termux app
itself, built for its own package. So updating is a re-copy plus a re-apply of a
short, deliberately small set of local changes.

The current source commit is recorded at the top of
[`src/terminal/README.md`](src/terminal/README.md). Keep it accurate: it is the
only thing that makes the next update a diff rather than a guess.

> **Licence:** `termux/termux-app` is **GPLv3-only**. The early notes in `plan/`
> claimed these libraries were Apache-2.0; they are not. See
> [`src/terminal/LICENSE.md`](src/terminal/LICENSE.md) before copying anything.

---

## 1. Get the new source

```bash
git clone --depth 1 https://github.com/termux/termux-app
cd termux-app
git rev-parse HEAD        # write this down, it goes in src/terminal/README.md
```

Nothing else is needed — no APK, no NDK toolchain beyond the one the project
already uses.

## 2. See what actually changed

Before copying anything, diff the new tree against the commit currently recorded
in `src/terminal/README.md`:

```bash
git log --oneline <recorded-sha>..HEAD -- terminal-emulator terminal-view
git diff <recorded-sha>..HEAD -- terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java
```

`TerminalSession.java` is the one file we modified, so read its diff properly.
Everything else can be copied over without much thought.

## 3. Copy the files

Same five sets as the original vendoring:

| From `termux-app/` | To `src/terminal/src/main/` |
|---|---|
| `terminal-emulator/src/main/java/com/termux/terminal/` | `java/com/termux/terminal/` |
| `terminal-view/src/main/java/com/termux/view/` | `java/com/termux/view/` (with `support/` and `textselection/`) |
| `terminal-view/src/main/res/values/strings.xml` | `res/values/strings.xml` |
| `terminal-view/src/main/res/drawable/text_select_handle_{left,right}_material.xml` | `res/drawable/` |
| `terminal-emulator/src/main/jni/termux.c` | `jni/termux.c` |

Do **not** copy: the upstream `build.gradle` files (ours is a single merged
library module), `proguard-rules.pro` (ours is `consumer-rules.pro`), the unit
tests under `terminal-emulator/src/test/`, or `AndroidManifest.xml`.

**Do not overwrite our own files**, which live in the same directories:

- `src/main/java/com/termux/terminal/XiaoianPty.java`
- `src/main/jni/pty_open.c`
- `src/main/jni/ptyspawn.c`

## 4. Re-apply the four divergences

These are the entire local delta. `src/terminal/README.md` lists them too; keep
both in step.

### A. One module, namespace `com.termux.view`

Upstream has two modules (`com.termux.emulator` and `com.termux.view`); we have
one. The namespace must stay `com.termux.view`, because the project sets
`android.nonTransitiveRClass=true` — a library's generated R class is
`<namespace>.R` — and the text-selection classes do `import com.termux.view.R`.
Nothing under `com.termux.terminal` touches resources, which is what makes
merging them safe.

Nothing to do here unless upstream adds a resource reference to
`com.termux.terminal`. If it does, the build will fail with an unresolved `R`,
and the fix is to move that resource use or split the module again.

### B. No `-Werror` in the native build

`src/terminal/build-natives.sh` compiles with `-Wall -Wextra` but not
`-Werror`. Leave it that way: a future NDK bump should not fail the build over
vendored code.

### C. `TerminalSession` can adopt an existing pty

This is the only edited upstream file. Every change is marked `XIAOIAN`, in six
places:

| Roughly where | What it is |
|---|---|
| after the constructor | The `PtyStarter` interface, the `mPtyStarter` field, `setPtyStarter()`, and `reportAdoptedPtyClosed()` with its `mAdoptedPtyExitReported` guard |
| in `initializeEmulator()` | `if (mPtyStarter != null)` takes the fd and pid from the starter instead of `JNI.createSubprocess()` |
| the reader thread | a `finally` block calling `reportAdoptedPtyClosed()` |
| the waiter thread | wrapped in `if (mPtyStarter == null)` |
| `finishIfRunning()` | delegates to `mPtyStarter.stop()` when one is set |

Find them in the current file with:

```bash
grep -n XIAOIAN src/terminal/src/main/java/com/termux/terminal/TerminalSession.java
```

Re-apply them to the new copy. **Why they exist:** everything Xiaoian runs in a
terminal is root, and `JNI.createSubprocess()` forks with the app's uid, so the
upstream path would mean exec'ing `su` once per session — a Magisk prompt or
toast every time. Because the adopted process is then not the app's child,
`waitpid()` cannot report its exit (hence EOF on the pty master as the end
signal) and `Os.kill()` cannot reach it (hence the delegated teardown). With no
starter set the class behaves exactly as upstream. The full reasoning is in
[ARCHITECTURE.md](ARCHITECTURE.md#the-terminal).

### D. `XiaoianPty.java` is a new file in a vendored package

It lives in `com.termux.terminal` because it needs the package-private `JNI`
class. `openPty()` does what `createSubprocess()` does up to the fork and stops
there. Just make sure step 3 did not delete it.

## 5. Rebuild the natives

```bash
sh src/terminal/build-natives.sh
```

This produces `libtermux.so` (from `termux.c` **and** our `pty_open.c`) and
`libptyspawn.so`, strips them, and then verifies five symbols with `llvm-nm`:

```
Java_com_termux_terminal_JNI_createSubprocess
Java_com_termux_terminal_JNI_setPtyWindowSize
Java_com_termux_terminal_JNI_waitFor
Java_com_termux_terminal_JNI_close
Java_com_termux_terminal_XiaoianPty_openPty
```

**Do not skip this step and do not ignore its output.** The `.so` files under
`jniLibs/` are prebuilts; no Gradle task regenerates them. A prebuilt that has
drifted from the source beside it compiles and installs perfectly happily and
then crashes on first use — which is exactly what `libanland_consumer.so` once
did, missing a symbol its Java side declared. The `llvm-nm` check is the only
thing standing between you and that.

If upstream adds a native method, add its symbol to the list in the script.

## 6. Check what upstream changed underneath us

A new upstream release most often breaks the app side, not the vendored side.
Two interfaces to look at:

- **`TerminalSessionClient`** and **`TerminalViewClient`.** New methods there
  become compile errors in `TerminalSessions.StoreClient` and
  `TerminalActivity.ViewClient`. That is the good case — a silently changed
  *default* would be worse, so read the diff even if it compiles.
- **`JNI`.** A changed signature means `build-natives.sh`'s symbol list and
  `src/terminal/consumer-rules.pro` need the same change.

Also re-check `consumer-rules.pro` if classes moved: it keeps
`com.termux.terminal.JNI`'s native methods by name (JNI binds by symbol name,
and `:app`'s release build has `minifyEnabled true`) and the whole of
`com.termux.view.textselection.**`.

## 7. Record and verify

1. Update the commit SHA and, if the file sets changed, the table in
   [`src/terminal/README.md`](src/terminal/README.md).
2. `cd src && ./gradlew.bat :terminal:assembleRelease` — must be clean.
3. `./gradlew.bat :app:assembleRelease` too, not just debug: R8 is where JNI
   keep rules and the reflective text-selection code break.
4. On the device, open a local terminal and check the things that depend on the
   parts we touched:
   - a prompt appears at all (the adopted pty works),
   - `Ctrl-C` interrupts and `vim` draws (controlling terminal, job control),
   - typing `exit` ends the session and the tab shows `· ended` (EOF detection —
     this is the divergence most likely to break silently),
   - long-press selection shows the handles (release build, shrunk resources).
