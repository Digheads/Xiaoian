#!/bin/bash
# Builds one of Anland's chroot-side components (see version.txt) as Debian
# packages. CI runs it on an arm64 runner in a debian:trixie container.
#
#   bash anland-chroot/build.sh deps  <kwin|xwayland>           (root, Debian)
#   bash anland-chroot/build.sh build <kwin|xwayland> <outdir>
#
# The recipe is upstream's own (lfdevs/anland-termux, build-kwin.yml and
# build-xwayland.yml): origtargz fetches the upstream source tarball from the
# Debian archive, gbp buildpackage builds from the packaging repo.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=/dev/null
. <(grep -E '^[A-Z_]+=' "$HERE/version.txt")

deps() {
    # deb-src entries, for build-dep. The trixie image ships deb822 sources.
    sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/debian.sources
    apt-get update
    apt-get build-dep -y "$1"
    apt-get install -y --no-install-recommends \
        ca-certificates git ccache build-essential devscripts fakeroot \
        quilt git-buildpackage pristine-tar zip
}

build() {
    local what="$1" out="$2" repo tag work debs
    case "$what" in
        kwin)     repo="$KWIN_REPO";     tag="$KWIN_TAG" ;;
        xwayland) repo="$XWAYLAND_REPO"; tag="$XWAYLAND_TAG" ;;
        *) echo "unknown component: $what" >&2; exit 2 ;;
    esac
    mkdir -p "$out"
    out=$(cd "$out" && pwd)
    work=$(mktemp -d)

    git config --global user.email "ci@xiaoian.invalid"
    git config --global user.name "Xiaoian CI"
    git clone -q --depth 1 -b "$tag" "$repo" "$work/$what"
    cd "$work/$what"

    # debhelper calls cc/c++ from PATH; ccache's wrappers go first.
    export PATH="/usr/lib/ccache:$PATH"

    origtargz
    if [ "$what" = kwin ]; then
        # This repo holds only debian/. origtargz unpacks the upstream source
        # beside it, and gbp builds only what is committed.
        git switch -q -c ci-build
        git add .
        git commit -q -m "Add upstream source"
    fi
    gbp buildpackage -uc -us -jauto --git-ignore-branch

    # The packages land beside the source tree. Development and debug-symbol
    # packages are not installed into the chroot.
    cd "$work"
    debs=$(find . -maxdepth 1 -type f -name '*.deb' \
               ! -name '*-dev_*' ! -name '*-dbgsym_*' -printf '%P\n' | sort)
    [ -n "$debs" ] || { echo "no packages were built" >&2; exit 1; }

    case "$what" in
        kwin)
            # One zip for the lot, named after the tag, which is what the KDE
            # script looks for: kwin_anland-...-debian-....zip
            # shellcheck disable=SC2086
            zip -j "$out/kwin_${tag}.zip" $debs
            ;;
        xwayland)
            # shellcheck disable=SC2086
            cp $debs "$out/"
            ;;
    esac

    echo "Packages from $repo $tag:"
    echo "$debs" | sed 's/^/  /'
    ls -l "$out"
    rm -rf "$work"
}

case "${1:-}" in
    deps)  deps "${2:?usage: build.sh deps <kwin|xwayland>}" ;;
    build) build "${2:?usage: build.sh build <kwin|xwayland> <outdir>}" \
                 "${3:?usage: build.sh build <kwin|xwayland> <outdir>}" ;;
    *)     echo "usage: $0 deps <kwin|xwayland> | build <kwin|xwayland> <outdir>" >&2; exit 2 ;;
esac
