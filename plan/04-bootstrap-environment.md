# 04 — Built-in Linux Environment

> **Rewritten 2026-09-18.** The original plan bootstrapped a Termux `$PREFIX`
> under `files/usr`. That approach was abandoned before it was built — see
> [Why not the Termux bootstrap](#why-not-the-termux-bootstrap). This document
> describes what the app actually does.

## Overview

The app needs a Linux environment of its own so that nothing depends on Termux
being installed. It gets this from a **Debian tool rootfs** it downloads on first
run, not from a Termux bootstrap.

There are two separate Debian trees on the device, and keeping them apart is the
key to reading the code:

| Tree | Path | Purpose | Size |
|---|---|---|---|
| **Tool rootfs** | `/data/data/com.xiaoian.app/files/rootfs` | Backs the in-app terminal. Never runs a desktop, and the scripts no longer touch it. | ~150 MB |
| **Desktop chroot** | `/data/local/xiaoian-x11-xfce/debian`<br>`/data/local/xiaoian-wayland-kde/debian` | The actual XFCE / KDE environment, one per DE. | 4–8 GB |

Both are extracted from the **same** tarball, downloaded once, and both take
their downloaded components from the same directory:

```
/data/data/com.xiaoian.app/files/downloads/
```

This used to be `$INFRA_ROOT/install_files`, one copy per desktop. Sharing it
means the Mesa driver is fetched once instead of twice, and uninstalling one
desktop no longer discards the other's components. There is no migration from
the old location: it landed before the first release, so a fresh install is the
only path onto it.

Because the assets are no longer inside an environment, the dashboard cards show
a single figure — the infra root's total — instead of splitting it into system
and installer files.

## Why not the Termux bootstrap

The original plan (bundle or download `bootstrap-aarch64.zip`, extract to
`files/usr`, `apt install` from Termux repos) does not survive contact with two
risks already recorded in [09](09-risks-and-mitigations.md):

- **[R10](09-risks-and-mitigations.md#r10-termux-packages-have-a-hardcoded-prefix)** —
  Termux packages are built with `/data/data/com.termux/files/usr` compiled into
  their binaries as an absolute path. They do not relocate. A Termux bootstrap
  unpacked under `com.xiaoian.app` produces binaries that look for their
  libraries, interpreters and config under a directory belonging to a different
  app, which is unreadable even when it exists.
- **[R11](09-risks-and-mitigations.md#r11-wx-exec-restriction)** — with
  `targetSdk` ≥ 29, Android refuses to exec binaries out of an app's data
  directory (W^X). Termux is exempt because it targets an older SDK; a new app
  is not.

A Debian arm64 rootfs has neither problem: it is entered with `chroot`, so its
own `/usr` is the absolute path its binaries expect, and `chroot` is executed by
root from `/system/bin`, not from app-owned storage.

Costs accepted in exchange: a larger first download than a Termux bootstrap, and
the tool rootfs duplicating packages the desktop chroot also has.

## Layout

```
/data/data/com.xiaoian.app/
├── files/
│   ├── rootfs/                       ← tool rootfs (Debian Trixie arm64)
│   │   ├── bin/bash
│   │   ├── bin/tar
│   │   ├── usr/bin/wget
│   │   └── usr/bin/apt-get
│   ├── downloads/                    ← shared by both desktops, persistent
│   │   ├── rootfs-arm64.tar.xz       ← source for every chroot
│   │   ├── mesa-for-android-container_*.tar.gz
│   │   ├── xwayland_*.deb            ← KDE only
│   │   └── kwin_anland-*.zip         ← KDE only
│   └── tmp/                          ← $TMPDIR; bind-mounted as the
│       └── anland.sock                 desktop chroot's /tmp
└── cache/                            ← scratch only; Android may wipe it
```

### Why the tarball is not in `cacheDir`

It used to be. Android evicts `cacheDir` whenever it wants, and the desktop
scripts read that same file when they install their chroot — so an eviction left
the script failing with *"Rootfs tarball not found … run the app setup first"*
and no way to recover, because `BootstrapManager` only re-downloads when the
**tool** rootfs is missing. It now lives under `filesDir`, which Android does not
touch, and `XiaoianService` calls `ensureRootfsTarball()` before starting a
script whose chroot is not installed yet.

## Bootstrap Flow

```
First start
    │
    ▼
BootstrapManager.isInstalled()            ← su test -f files/rootfs/bin/bash
    │                                          + files/rootfs/usr/bin/apt-get
    ├─ true  → nothing to do
    └─ false ↓
       1. ensureRootfsTarball()
          ├─ already ≥ 10 MB in files/downloads/ → done
          ├─ left over in cacheDir by an older version → move it
          └─ else: resolve latest image, download to .part, rename on success
       2. rm -rf files/rootfs, mkdir
       3. XZInputStream → plain .tar (Android's tar has no xz support)
       4. su tar -xf … -C files/rootfs
       5. verify bin/bash and usr/bin/apt-get exist
       6. configureNetwork(): resolv.conf + aid_inet/aid_net_raw groups
       7. installPackages(["wget", "tar", "xz-utils"])
          └─ su chroot files/rootfs apt-get update && apt-get install -y …
```

### Image source

`https://images.linuxcontainers.org/streams/v1/images.json` is scanned for the
newest `debian/trixie/arm64/default/<date>` entry, which yields:

```
https://images.linuxcontainers.org/images/debian/trixie/arm64/default/<date>/rootfs.tar.xz
```

The same URL shape the scripts used before the download moved into the app.

### Networking inside the chroot

Android gates network access on supplementary group membership. `apt` drops to
the `_apt` user, which is in no Android group, so `configureNetwork()` appends:

```
aid_inet:x:3003:_apt
aid_net_raw:x:3004:_apt
```

`resolv.conf` is a dangling systemd symlink in the LXC image and is replaced with
a real file (`nameserver 8.8.8.8`).

## What the tool rootfs is *not* used for

It briefly supplied `wget` and `tar` to the scripts through
`chroot files/rootfs …`. That could never work: `chroot` resolves every path
inside the new root, and the scripts pass host paths (`$INSTALLER_DIR`, the
tarball, the destination chroot). A fresh XFCE install failed on exactly that.
See [R14](09-risks-and-mitigations.md#r14-tool-rootfs-cannot-see-host-paths).

The scripts now do their own I/O without a chroot:

| Operation | Mechanism |
|---|---|
| Downloads | `http_get` / `http_cat` → `com.xiaoian.app.tools.Fetch` via `app_process` |
| `.tar.xz` extraction | `extract_txz` → `com.xiaoian.app.tools.Cat` piped into busybox `tar -xJf -` |

So the tool rootfs has exactly one consumer left: the in-app terminal, which
chroots into it. A failed install no longer aborts a desktop session.

## `BootstrapManager` API

| Member | Purpose |
|---|---|
| `prefixDir` | `files/rootfs` |
| `rootfsTarball` | `files/downloads/rootfs-arm64.tar.xz` |
| `isInstalled()` | `su test -f` on `bin/bash` + `usr/bin/apt-get` — the tree is root-owned, so `File.exists()` cannot see it |
| `ensureRootfsTarball(onProgress)` | Downloads the tarball if absent; migrates one left in `cacheDir`; `.part` + rename so a broken download is never mistaken for a cached one |
| `installBootstrap(onProgress)` | Full tool-rootfs install (the flow above) |
| `installPackages(pkgs, onProgress)` | `apt-get update && apt-get install -y` inside the tool rootfs |

Everything touching the rootfs goes through `su`, per
[R2 M2a](09-risks-and-mitigations.md#r2-selinux-context-issues).

## Error Recovery

| Failure | Current behaviour |
|---|---|
| Tarball download interrupted | `.part` is discarded; the next start re-downloads |
| Tarball evicted / deleted | Re-downloaded before the script runs |
| Extraction fails | `installBootstrap` returns false; logged as a warning, the session continues without the terminal |
| `apt-get` fails | Reported through `onProgress`; the tool rootfs is left in place |
| Corrupt tool rootfs | `installBootstrap` does `rm -rf` first, so a retry is a clean re-install |

Not yet implemented: a user-visible "reset environment" action, and any
versioning of the tool rootfs (it is never refreshed once installed).

## Remaining Work

| Item | Notes |
|---|---|
| Install the tool rootfs on demand | Nothing on the desktop path needs it any more, yet a first session still downloads ~150 MB and runs `apt` for it. It belongs behind the terminal screen instead |
| Surface `files/downloads` in the UI | Now that it sits outside both environments, nothing in the app shows its size or offers to clear it |
| Free the tarball after both desktops are installed | ~250 MB held indefinitely today |
| Tool rootfs versioning / refresh | No marker file, no upgrade path |
| "Reset environment" in Settings | Settings screen is still a stub |
