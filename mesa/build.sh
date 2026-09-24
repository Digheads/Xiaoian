#!/bin/bash
# Builds the Mesa driver package the chroot scripts install: a tarball of a
# plain /usr tree, unpacked over the Debian rootfs. Glibc, not bionic, so this
# cannot be part of the Gradle/NDK build; CI runs it on an arm64 runner in a
# debian:trixie container (.github/workflows/release.yml).
#
#   bash mesa/build.sh deps          install build dependencies (root, Debian)
#   bash mesa/build.sh build <outdir> build and package into <outdir>
#
# The configuration is lfdevs' own Debian build, so the package matches the one
# the scripts used to download. Panfrost is deliberately not in it yet: on a
# stock Android kernel it needs the out-of-tree kbase patches anyway, and
# adding a driver nobody can test only adds ways for this build to break.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=/dev/null
. <(grep -E '^[A-Z_]+=' "$HERE/version.txt")

deps() {
    # deb-src entries, for build-dep. The trixie image ships deb822 sources.
    sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/debian.sources
    apt-get update
    apt-get build-dep -y mesa
    apt-get install -y --no-install-recommends \
        ca-certificates git ccache libxfixes-dev libarchive-dev
}

build() {
    local out="$1" work src stage version name
    mkdir -p "$out"
    out=$(cd "$out" && pwd)
    work=$(mktemp -d)
    src="$work/mesa"
    stage="$work/stage"

    # A single commit, not a branch: fetching by hash keeps the build
    # reproducible even after the fork moves on.
    git init -q "$src"
    git -C "$src" fetch -q --depth 1 "$MESA_REPO" "$MESA_COMMIT"
    git -C "$src" checkout -q FETCH_HEAD

    version=$(grep -v '^[[:space:]]*$' "$src/VERSION" | head -n1)
    # No build date in the name: the same pin gives the same file name, so a
    # phone that already has it does not download it again.
    name="xiaoian-mesa_${version}-${MESA_COMMIT:0:7}_debian_trixie_arm64.tar.gz"

    # Meson picks up ccache by itself when it is on PATH.
    meson setup "$work/build" "$src" \
        --prefix=/usr \
        --buildtype=release \
        -Dplatforms=x11,wayland \
        -Dgallium-drivers=freedreno,zink,virgl,llvmpipe \
        -Dgallium-va=disabled \
        -Dgallium-mediafoundation=disabled \
        -Dvulkan-drivers=freedreno \
        -Dvulkan-layers= \
        -Degl=enabled \
        -Dgles2=enabled \
        -Dglvnd=enabled \
        -Dglx=dri \
        -Dlibunwind=disabled \
        -Dintel-rt=disabled \
        -Dmicrosoft-clc=disabled \
        -Dvalgrind=disabled \
        -Dgles1=disabled \
        -Dfreedreno-kmds=kgsl
    ninja -C "$work/build"
    DESTDIR="$stage" meson install -C "$work/build" --no-rebuild

    # Headers and pkg-config files are for building against Mesa, which
    # nothing in the chroot does; the runtime needs neither.
    rm -rf "$stage/usr/include"
    find "$stage" -type d -name pkgconfig -prune -exec rm -rf {} +
    find "$stage" -type f -name '*.so*' -exec strip --strip-unneeded {} +

    tar -czf "$out/$name" --owner=0 --group=0 --numeric-owner -C "$stage" .
    (cd "$out" && sha256sum "$name" > "$name.sha256")

    echo "Package: $out/$name ($(du -h "$out/$name" | cut -f1))"
    tar -tzf "$out/$name" | grep -E 'libvulkan_|_icd\.|/(zink|kgsl|swrast)_dri\.so$' || true
    rm -rf "$work"
}

case "${1:-}" in
    deps)  deps ;;
    build) build "${2:?usage: build.sh build <outdir>}" ;;
    *)     echo "usage: $0 deps | build <outdir>" >&2; exit 2 ;;
esac
