# Xiaoian

**A Debian desktop on your Android phone.** KDE Plasma 6 over Wayland or XFCE 4
over X11, running in a `chroot`, driven from an ordinary Android app.

Everything is in the app. There is no Termux to install, no display server to
side-load, no shell command to remember: the app downloads the Debian rootfs,
installs the packages, starts the session, and shows the desktop in a window of
its own.

| | |
|---|---|
| **KDE Plasma 6** | Wayland, rendered by the built-in Anland frontend |
| **XFCE 4** | X11, rendered by the built-in Termux:X11 frontend |
| **Terminal** | A real pty with job control, in the desktop chroot or in a small tool rootfs of its own |

### The three documents

| | |
|---|---|
| **This file** | What the app does and how to use it |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the app is put together, and how to build and work on it |
| [original/README.md](original/README.md) | The older standalone shell scripts, run by hand from Termux |

---

## Requirements

| | |
|---|---|
| **A rooted phone** | Magisk, KernelSU or APatch. The app never looks for a particular one. |
| **Android 9 or newer** | arm64 only. |
| **~8 GB free** | The Debian rootfs plus packages. KDE is the larger of the two. |
| **An internet connection** | For the first install of each desktop. Later starts work offline. |
| **An external display** | Only for *extend* and *mirror* mode — wired HDMI, USB-C DisplayPort, or Miracast. *Local* mode runs on the phone screen. |

You will be asked for root the first time the app needs it, and normally not
again: the app keeps a single root shell open for as long as it runs, instead of
launching `su` per command.

### Supported devices

The first time the app opens it checks the phone and says whether it is
supported. The **ⓘ** button at the top left of the dashboard opens a screen
with the same check, the display settings extend and mirror mode need together
with their current values, and the list below.

| | |
|---|---|
| **Tested** | Xiaomi Redmi Note 13 (4G) — Snapdragon 685 / Adreno 610, HyperOS 2 (Android 15) |
| **Expected to work** | arm64 phones with a Snapdragon (Adreno) GPU, Android 11+, rooted with Magisk |
| **Limited** | Mali, Xclipse, MediaTek and Tensor (Pixel) GPUs: XFCE only with software rendering, no KDE. Kernels before 5.11: the virtual lock cannot disable the touchscreen |
| **Untested** | Samsung (DeX), KernelSU, APatch |
| **Not supported** | 32-bit or x86 devices, phones without root |

KDE needs Android 11; XFCE runs from Android 9.

---

## First start

1. Open the app. The dashboard lists both desktops with the space they use, or
   *Not installed*.
2. Pick **KDE (Wayland)** or **XFCE (X11)**, pick a display mode, and press
   **START DESKTOP**.
3. The first start of each desktop asks for a **root password** for it — each
   desktop has its own. The app does not keep it; change it later with
   `passwd` in that desktop's terminal.
4. The first start installs everything: the Debian rootfs, the GPU drivers, and
   the desktop's packages. Expect **10–30 minutes**, depending on your network
   and phone. Progress is shown on the dashboard and in the notification, step
   by step — including live `apt` progress.
5. When it is ready the desktop window opens by itself. You can also reopen it
   later with **OPEN DESKTOP**.

The session survives leaving the app. A notification keeps it visible with
**Stop**, **Lock/Unlock** and **Preferences** buttons.

---

## Display modes

| Mode | What it does |
|---|---|
| **Extend** *(default)* | The desktop gets the external display to itself; the phone screen stays yours. |
| **Mirror** | The desktop appears on both the phone and the external display. |
| **Local** | The desktop runs on the phone screen. No external display needed. |

In extend and mirror mode the notification (and the running-session card) offers
**Lock**, which blanks and locks the phone while the desktop keeps running on
the external display. It asks first, and says how to get back: press
**Volume Down twice**, quickly (or use **Unlock** in the notification). Each
press also lowers the volume by a step. **Power** unlocks too, but the phone
treats it as a real screen-off: wake it as usual, and touch works again. Lock is hidden in local mode, where the
phone screen *is* the desktop.

In extend mode the running-session card also has **Show input**, which turns
the phone into a touchpad and keyboard for the desktop:

- one finger moves the pointer;
- a tap is a click, and a two-finger tap is a right click;
- two fingers scroll;
- a tap followed by a touch-and-drag holds the button down, for dragging.

The button at the bottom right brings up the phone's keyboard with the same
special-keys bar as under the terminal, using the same layout from Settings. A
modifier applies to the next key; a long press keeps it on. Touching
the touchpad puts the keyboard away. The touchpad closes by itself when the
session stops, leaves extend mode or is locked.

**Extend and mirror are greyed out until an external display is connected** —
both put the desktop on a screen that has to exist, and the scripts refuse the
start outright without one. Plug a display in and they become selectable; unplug
it and the selection falls back to local.

**With more than one external display connected**, extend and mirror mode add an
**External Display** list to the dashboard, naming each screen and its
resolution. The list follows what is actually plugged in, so connecting or
unplugging a screen updates it while you are looking at it. With a single
external display there is nothing to choose and no list appears.

The session stays on the display you picked, including when you **Lock** and
unlock the phone. If that screen is gone by the time the desktop starts, the app
falls back to whichever external display it can find and says so in the log.

---

## The terminal

**TERMINAL** on the dashboard opens a proper terminal: a real pty, so job
control works (`Ctrl-C` interrupts, `Ctrl-Z` suspends), and full-screen programs
like `vim`, `htop` and `less` behave the way they should. Colours, mouse
reporting and text selection all work.

It can open four kinds of session:

| | |
|---|---|
| **Xiaoian** | A small Debian bootstrap environment that belongs to the app. It needs no desktop at all, and the app installs it the first time you ask for it. |
| **Android** | A root shell on Android itself, like `adb shell` followed by `su`: `dumpsys`, `am`, `pm`, `settings`, `logcat` and the rest all work. Needs nothing installed. |
| **XFCE** | A shell inside the XFCE desktop's Debian tree. |
| **KDE** | A shell inside the KDE desktop's Debian tree. |

In a **Xiaoian** terminal, `android` runs an Android command without leaving
Debian, so the two can share a pipeline — `android dumpsys input | grep -i touch`,
`android settings list global | less`. On its own, `android` opens an Android
shell in place; `exit` brings you back.

**Neither chroot needs its desktop to be running.** If the desktop is up, the
session joins it — `DISPLAY` or the Wayland socket is set, so you can start GUI
programs onto the live desktop. If it is not, you get a plain root shell in the
same tree, and the terminal says so.

### Tabs

The tab strip runs along the top. **+** opens a new session and asks which of
the three you want. **Long press a tab** for its menu:

- **New session** — same as **+**
- **Rename** — up to 15 characters; leave it empty to go back to the default name
- **Close** — ends the session and everything it started

A session that exits on its own keeps its tab, greyed out with its name struck
through, so you can still read why. The same goes for the terminals of a
desktop that stops, logs out or crashes: their shells end with it, but the tabs
and their output stay. Press **Enter** in such a tab to close it.

### The key bar

The row of extra keys at the bottom is the same one the KDE desktop uses, and
it reads the same layout — edit it in either place and both follow. `CTRL`,
`ALT` and `SHIFT` toggle on a tap and **lock on a long press**. They apply to
keys from the bar *and* to characters you type on the soft keyboard, so
bar-`CTRL` plus `c` sends `^C`.

The **gear key** on the bar opens the app's settings, where the layout lives.

### Reaching your phone's files

Your internal storage is at the **same path everywhere** — in a local terminal,
in a chroot terminal, and on either desktop:

```
/mnt/android/storage/emulated/0
```

So `/mnt/android/storage/emulated/0/DCIM` is your camera roll, from anywhere.
Two shortcuts exist so you never have to type that:

| | |
|---|---|
| `/android` | In a **local** terminal, a symlink to `/mnt/android` |
| `~/Storage` | On a **desktop**, a symlink to your internal storage — it shows up in the file manager's Home |

A **local** terminal gets more than storage: the whole Android filesystem is
there, so `/android/system`, `/android/data` and the rest are all browsable. The
desktops get only the storage branch of that tree, which is the part worth
having and the part that is safe to unmount afterwards.

> `/mnt/android/sdcard` is a symlink that points outside the chroot, so it does
> not resolve. Use the `storage/emulated/0` path above.

Files you save from the desktop land in your phone's storage exactly as if an
Android app had written them, so they show up in Gallery and Files normally.

The local terminal's mount is released once the last live local session is gone;
the desktops' is released when the session stops.

---

## Settings

The **gear** in the top right of the dashboard — and the gear key on the
terminal's key bar — opens the app's settings:

| | |
|---|---|
| **Extra keys bar** | The layout, as JSON, with the built-in template one tap away and an import from file. This bar is shared by the terminal and the KDE desktop. |
| **Terminal** | Font size, and whether the key bar is shown at all. |

Below those are links to each desktop's own settings, which are separate
screens belonging to the two display frontends: the KDE one covers the display,
touchpad, audio and the display daemon; the XFCE one covers resolution,
scaling and filtering, pointer and scancode handling — **and its own key bar**,
which is a different bar in a different format from the one above.

While a desktop is running, **Preferences** in the notification goes straight
to that desktop's screen.

---

## Stopping, and getting rid of things

- **STOP** on the running card, or **Stop** in the notification, ends the
  session: terminals in that chroot are closed first, then the desktop is torn
  down and its mounts released, and the desktop window closes.
- Logging out from inside the desktop does the same thing from the other
  direction; the app notices and tidies up after it.
- The **bin icon** on an environment card removes that desktop's whole Debian
  tree. It is only offered while nothing is running.

---

## When something goes wrong

**The desktop window is black and then closes.** Usually the compositor could
not reach the display daemon. Stop the session and start it again; if it
persists, the desktop's log is the place to look —
`/data/local/xiaoian-wayland-kde/` for KDE, `/data/local/xiaoian-x11-xfce/` for
XFCE.

**Root keeps being refused.** If a Magisk prompt is dismissed or times out, the
app waits 20 seconds before asking again, so it cannot flood you with prompts.
Check the rule in your root manager, then try again. Rebooting the phone also
resets a `shell` grant.

**The terminal says the environment is not installed.** For a desktop chroot,
install that desktop from the dashboard first. For a local session the app
installs the bootstrap environment itself, so this only means the install failed —
check your connection and free space.

**A session was left behind after the app was killed.** The app notices on its
next start and cleans up the leftovers, so nothing stays in the chroot blocking
the next unmount.

---

## Release build

`./gradlew :app:assembleRelease` (in `src/`) produces a signed
`app-release.apk` when `src/keystore.properties` exists:

```
storeFile=xiaoian-release.jks
storePassword=...
keyAlias=xiaoian
keyPassword=...
```

Both that file and the keystore are git-ignored. **Keep a copy of both**:
Android only installs an update that is signed with the same key, so without
them no update can be released for devices that already have the app. Without
`keystore.properties`, the release build is unsigned and cannot be installed.

A release build cannot be installed over a debug build, or the other way
round, because they are signed with different keys. Uninstall the other one
first. This removes the app's settings, but not the installed desktops, which
live under `/data/local`.

---

## Licence

GPLv3. See [LICENCE](LICENCE). The app contains code from
[Termux:X11](https://github.com/termux/termux-x11), the
[Termux terminal libraries](https://github.com/termux/termux-app) (see
[src/terminal/LICENSE.md](src/terminal/LICENSE.md)) and
[Anland](https://github.com/lfdevs/anland-termux).
