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

---

## First start

1. Open the app. The dashboard lists both desktops with the space they use, or
   *Not installed*.
2. Pick **KDE (Wayland)** or **XFCE (X11)**, pick a display mode, and press
   **START DESKTOP**.
3. The first start installs everything: the Debian rootfs, the GPU drivers, and
   the desktop's packages. Expect **10–30 minutes**, depending on your network
   and phone. Progress is shown on the dashboard and in the notification, step
   by step — including live `apt` progress.
4. When it is ready the desktop window opens by itself. You can also reopen it
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
the external display. It is hidden in local mode, where the phone screen *is*
the desktop.

---

## The terminal

**TERMINAL** on the dashboard opens a proper terminal: a real pty, so job
control works (`Ctrl-C` interrupts, `Ctrl-Z` suspends), and full-screen programs
like `vim`, `htop` and `less` behave the way they should. Colours, mouse
reporting and text selection all work.

It can open three kinds of session:

| | |
|---|---|
| **Local** | A small Debian tool environment that belongs to the app. It needs no desktop at all, and the app installs it the first time you ask for it. |
| **XFCE** | A shell inside the XFCE desktop's Debian tree. |
| **KDE** | A shell inside the KDE desktop's Debian tree. |

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

A session that exits on its own keeps its tab, greyed out and marked `· ended`,
so you can still read why. Press **Enter** in it to close it.

### The key bar

The row of extra keys at the bottom is the same one the desktop frontend uses,
and it reads the same layout you can edit in the Anland settings. `CTRL`, `ALT`
and `SHIFT` toggle on a tap and **lock on a long press**. They apply to keys
from the bar *and* to characters you type on the soft keyboard, so bar-`CTRL`
plus `c` sends `^C`.

### `/mnt/android` — your phone's filesystem

In a **local** session the whole Android filesystem is mounted at
`/mnt/android`, so `/mnt/android/system`, `/mnt/android/data` and the rest are
all there. Your internal storage is at `/mnt/android/storage/emulated/0`.

> `/mnt/android/sdcard` is a symlink that points outside the chroot, so it does
> not resolve. Use the `storage/emulated/0` path above.

It is unmounted again once the last live local session is gone. The desktop
chroots deliberately do **not** get this mount — see
[ARCHITECTURE.md](ARCHITECTURE.md#mounts-and-mount-propagation) for
why.

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
installs the tool environment itself, so this only means the install failed —
check your connection and free space.

**A session was left behind after the app was killed.** The app notices on its
next start and cleans up the leftovers, so nothing stays in the chroot blocking
the next unmount.

---

## Licence

GPLv3. See [LICENCE](LICENCE). The app contains code from
[Termux:X11](https://github.com/termux/termux-x11), the
[Termux terminal libraries](https://github.com/termux/termux-app) (see
[src/terminal/LICENSE.md](src/terminal/LICENSE.md)) and
[Anland](https://github.com/lfdevs/anland-termux).
