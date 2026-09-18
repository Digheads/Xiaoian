# 09 — Risks and Mitigations

## Risk Matrix

| # | Risk | Probability | Impact | Priority |
|---|---|---|---|---|
| R1 | Anland.Termux source code not available / not forkable | Medium | Critical | 🔴 |
| R2 | SELinux blocks operations from Xiaoian app context | Medium | High | 🔴 |
| R3 | GPL license limits distribution (Google Play) | High | Medium | 🟡 |
| R4 | Android process killer terminates background processes | Medium | High | 🟡 |
| R5 | Termux bootstrap compatibility breaks on update | Low | High | 🟡 |
| R6 | Android 15+ restricts root shell / chroot operations | Low | Critical | 🟡 |
| R7 | External display APIs differ across OEMs | Medium | Medium | 🟡 |
| R8 | APK size too large | Low | Low | 🟢 |
| R9 | Performance regression vs standalone apps | Low | Medium | 🟢 |
| R10 | Termux packages have a hardcoded `$PREFIX` — a Termux bootstrap does not work under `com.xiaoian.app` | High | Critical (Phase 3) | 🔴 |
| R11 | targetSdk ≥ 29 blocks exec of binaries from the app's data dir (W^X) | High | High (Phase 3) | 🔴 |
| R12 | No sound without Termux (XFCE) | Certain | Medium | 🟡 |
| R13 | App downloader / busybox tar not available on a device | Low | High | 🟡 |

---

## R1: Anland Source Code Availability

**Risk:** The Anland.Termux Android app may be closed source or have an incompatible license, preventing us from forking the rendering code.

**Investigation steps:**
1. Check `lfdevs/anland-termux` GitHub repo for Android app source
2. Check the license file
3. Contact the lfdevs team if unclear

**Mitigations:**

| Strategy | Effort | Result |
|---|---|---|
| **M1a:** Keep Anland as separate app (Phase 2 partial) | 0 | User needs 2 apps for KDE: Xiaoian + Anland |
| **M1b:** Negotiate licensing with lfdevs | Variable | Depends on their willingness |
| **M1c:** Write own Wayland renderer (NDK) | 4–6 weeks | Full independence, but significant work |
| **M1d:** Focus on X11/XFCE only for v1.0 | 0 | KDE support delayed |

**Recommendation:** Start with M1a (keep Anland as fallback), investigate M1b in parallel. Only pursue M1c if there's strong user demand for a single-APK KDE experience.

---

## R2: SELinux Context Issues

**Risk:** When processes (anland, termux-x11, pulseaudio) are spawned from the Xiaoian app's data directory instead of Termux's, SELinux may block socket creation, file access, or IPC.

**Why it matters:** Android enforces per-app SELinux contexts. A binary in `/data/data/com.xiaoian.app/` runs in `u:r:untrusted_app:s0` context, which may have different permissions than the same binary in Termux's context.

**Mitigations:**

| Strategy | Details |
|---|---|
| **M2a:** Use `su` for all operations | Running via `su -c` switches to `u:r:su:s0` context (root), bypassing app-level SELinux restrictions. Since the scripts already require root, this is the natural path. |
| **M2b:** Set permissive for app domain | `su -c 'setenforce 0'` or `magiskpolicy --live 'allow untrusted_app ...'`. Heavy-handed but works on Magisk-rooted devices. |
| **M2c:** Run critical processes via su | Only the display servers and audio need root context; the terminal can run in app context. |

**Recommendation:** M2a. The scripts already run as root. The bootstrap $PREFIX processes should also be launched via `su -c` to avoid context issues.

**Testing:** Create a minimal test early in Phase 3: install bootstrap, run `bash` from Xiaoian app's $PREFIX via `su -c`, verify it works.

---

## R3: GPL License and Distribution

**Risk:** GPL-3.0 requires source code distribution with every binary. Google Play has historically been ambiguous about GPL apps, though many GPL apps exist on the Play Store (Termux itself was there until F-Droid became preferred).

**Components and their licenses:**

| Component | License | Implication |
|---|---|---|
| Xiaoian shell scripts | MIT | ✅ No issue |
| Termux `terminal-emulator` library | Apache-2.0 | ✅ No issue |
| Termux `terminal-view` library | Apache-2.0 | ✅ No issue |
| Termux:X11 renderer code | GPL-2.0 | ⚠️ App becomes GPL-2.0+ |
| Termux app bootstrap | GPL-3.0 | ⚠️ If bundled, app becomes GPL-3.0 |

**Mitigations:**

| Strategy | Details |
|---|---|
| **M3a:** Ship as GPL-3.0, distribute via F-Droid + GitHub | F-Droid is designed for FOSS apps. GitHub releases for direct APK download. Skip Google Play entirely. |
| **M3b:** Ship on Google Play AS GPL-3.0 | Legal. Include source code link in app description. Many apps do this. |
| **M3c:** Avoid GPL code entirely | Write own terminal emulator (possible, Apache-2.0 libs available), own X11 renderer (significant work), don't bundle bootstrap (download at runtime). This removes the GPL requirement but adds months of work. |

**Recommendation:** M3a. F-Droid + GitHub is the natural distribution channel for this kind of power-user, root-required tool. The target audience already uses F-Droid (that's where they get Termux).

---

## R4: Android Process Killer

**Risk:** Android may kill background processes (the display server, audio server, or the shell script session) to reclaim memory.

**Mitigations:**

| Strategy | Details |
|---|---|
| **M4a:** Foreground Service with notification | Standard Android pattern. The system is much less likely to kill processes associated with a foreground service. |
| **M4b:** `startForeground()` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` | Android 14+ requires declaring foreground service type. |
| **M4c:** Disable battery optimization for the app | Prompt user to whitelist the app from battery optimization (Doze). |
| **M4d:** `android:persistent="true"` in manifest | Only works for system apps, not applicable here. |

**Recommendation:** M4a + M4c. Foreground service is mandatory. Battery optimization whitelist should be prompted on first run.

---

## R5: Termux Bootstrap Compatibility

**Risk:** Termux updates their bootstrap format, repository URLs, or package structure, breaking the Xiaoian app's bootstrap flow.

**Mitigations:**

| Strategy | Details |
|---|---|
| **M5a:** Pin bootstrap version | Ship a known-good bootstrap tarball version. Only update when tested. |
| **M5b:** Host own bootstrap mirror | Fork and host the bootstrap tarball, independent of Termux infrastructure changes. |
| **M5c:** Bundle bootstrap in APK | Include the tarball in `assets/`, eliminating runtime download dependency. |

**Recommendation:** M5a + M5c hybrid. Bundle a minimal bootstrap in the APK, pin to a tested version. Update only with app releases.

---

## R6: Android Version Restrictions

**Risk:** Future Android versions (15+) may further restrict root operations, chroot, or mount commands.

**Mitigations:**

| Strategy | Details |
|---|---|
| **M6a:** Stay informed | Monitor Android security bulletins and Magisk/KernelSU development. |
| **M6b:** Test on Android betas | Test the app on Android developer previews before release. |
| **M6c:** Alternative: use `proot` as fallback | If chroot breaks, offer proot mode (no root, lower performance). |

**Recommendation:** M6a + M6b. This is a long-term risk that affects the entire rooted Android ecosystem, not just Xiaoian.

---

## R7: OEM Display API Differences

**Risk:** `DisplayManager.getDisplays()`, `am start --display`, and `dumpsys display` output may differ across OEMs (Samsung, Xiaomi, Huawei, etc.).

**Mitigations:**

| Strategy | Details |
|---|---|
| **M7a:** Comprehensive display detection | Try multiple detection methods: `DisplayManager` API, `dumpsys display` parsing (already in scripts), `MediaRouter` API. |
| **M7b:** Manual display selection | If auto-detection fails, let the user pick the target display from a list. |
| **M7c:** Community testing | Encourage users to report their device model and display setup results. |

**Recommendation:** M7a + M7b. The scripts already handle `dumpsys display` parsing robustly. Adding a manual override in the UI covers edge cases.

---

## R8: APK Size

**Risk:** Bundling bootstrap, native libraries, and renderer code may make the APK too large.

**Size estimates:**

| Component | Size |
|---|---|
| Kotlin/Compose app | ~5 MB |
| X11 renderer native libs (arm64) | ~2 MB |
| Anland renderer native libs (arm64) | ~2 MB |
| Terminal emulator library | ~1 MB |
| Minimal bootstrap tarball | ~15 MB |
| **Total** | **~25 MB** |

This is very reasonable. Termux itself is ~100+ MB after bootstrap.

**Mitigation:** If size is a concern, download the bootstrap on first run instead of bundling it. This brings the APK down to ~10 MB.

---

## R9: Performance Regression

**Risk:** The embedded renderer may be slower than the standalone apps due to additional abstraction layers.

**Mitigations:**

| Strategy | Details |
|---|---|
| **M9a:** Direct fork | Use exactly the same rendering code as the standalone apps, not a wrapper. |
| **M9b:** Profile early | Measure frame rate and input latency in Phase 2, compare with standalone apps. |
| **M9c:** Hardware acceleration | Ensure EGL/GLES rendering path is preserved (not software fallback). |

**Recommendation:** M9a. Since we're forking the exact rendering code, there should be zero performance difference. The renderer doesn't care which APK it lives in.

---

## R10: Termux Packages Have a Hardcoded Prefix

**Risk:** Termux binaries, scripts and `RUNPATH`s are built for `/data/data/com.termux/files/usr`. A Termux bootstrap extracted to `/data/data/com.xiaoian.app/files/usr` (the Phase 3 plan in [04](04-bootstrap-environment.md)) will not run without fixups: shebangs, library lookups and config paths all point to the Termux prefix.

**Mitigations:**

| Strategy | Details |
|---|---|
| **M10a:** Avoid a `$PREFIX` entirely | What XFCE already does: X server from the APK via `app_process`, downloads in the app, tar from the root solution's busybox. Remaining host-side tools run inside the Debian chroot instead. |
| **M10b:** Rebuild the needed packages with a custom prefix | termux-packages supports building for a different package name; high maintenance cost. |
| **M10c:** Keep Termux as an optional dependency | Only for components that cannot be replaced yet (PulseAudio, `anland`). |

**Recommendation:** M10a, with M10c as a bridge. Re-evaluate Phase 3's bootstrap plan before starting it.

---

## R11: W^X Exec Restriction

**Risk:** With `targetSdk` ≥ 29, an app process may not `exec` files from its own writable data directory. Termux stays on targetSdk 28 for this reason. The app targets 35, so a `$PREFIX` in the app's data dir cannot be executed from the app's own context (for example the Phase 3 "Local" terminal session).

**Mitigations:** run such binaries through `su` (root is required anyway), or ship the executables as `lib*.so` in `jniLibs` (extracted to the read-only native library dir, which may be executed).

---

## R12: Sound Without Termux

**Risk:** XFCE sound still uses Termux PulseAudio (`module-sles-sink`). Without Termux the desktop runs silently. Termux:X11 has no audio forwarding that could replace it.

**Options:**

| Strategy | Details |
|---|---|
| **M12a:** PulseAudio/PipeWire inside the chroot + an Android sink in the app | The app plays PCM from a unix socket with `AudioTrack` (and `AudioRecord` for the mic). This is essentially what Anland does for KDE. |
| **M12b:** Keep Termux PulseAudio as optional | Current state: works if Termux + `pulseaudio` are installed. |

---

## R13: Host Tool Availability (XFCE)

**Risk:** The XFCE script now depends on (a) the app's `com.xiaoian.app.tools.Fetch` running under `app_process` with working TLS, and (b) a busybox with xz-capable `tar` from Magisk / KernelSU / APatch for the first rootfs extraction. Neither has been tested on a device yet.

**Mitigations:** Termux wget/tar remain as fallbacks when Termux is installed. If busybox `tar -J` is missing on some root solution, the next step is to extract `.tar.xz` in the app (xz + tar Java library, preserving modes, owners and symlinks).
