#!/bin/sh
# Builds the two native artefacts of the terminal module from src/main/jni
# into src/main/jniLibs/arm64-v8a.
#
# Same reason as src/anland/build-natives.sh: no module in this project has an
# externalNativeBuild block (the SDK's CMake/ndk-build is not installed and is
# not going to be), so the .so files under jniLibs are prebuilts that drift
# from the sources beside them unless this is run. Run it after touching
# anything under src/main/jni.
#
# Upstream builds termux.c with ndk-build and -Werror; the flags below are the
# same minus -Werror, which is not worth failing a vendored-code build over.
# API 28 matches minSdk: grantpt/unlockpt/ptsname_r/clearenv are all available
# there, unlike the anland consumer's memfd_create() which forced API 30.
set -eu

NDK="${ANDROID_NDK:-C:/Program Files (x86)/Android/android-sdk/ndk/29.0.14206865}"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
CC="$BIN/aarch64-linux-android28-clang"
STRIP="$BIN/llvm-strip"
NM="$BIN/llvm-nm"

cd "$(dirname "$0")/src/main/jni"
OUT=../jniLibs/arm64-v8a
mkdir -p "$OUT"

# The JNI library behind com.termux.terminal.JNI and XiaoianPty. termux.c only
# uses libc -- no android/log.h -- so it needs no -llog. pty_open.c is ours;
# it rides in the same .so so there is only one System.loadLibrary.
"$CC" -std=c11 -Wall -Wextra -Os -fPIC -shared -fuse-ld=lld \
    -fno-stack-protector -ffunction-sections -fdata-sections -Wl,--gc-sections \
    termux.c pty_open.c -o "$OUT/libtermux.so"

# An executable, named lib*.so so the packager extracts it into
# nativeLibraryDir with the execute bit (see ptyspawn.c's header).
"$CC" -std=c11 -Wall -Wextra -Os -fPIE -pie \
    -fno-stack-protector -ffunction-sections -fdata-sections -Wl,--gc-sections \
    ptyspawn.c -o "$OUT/libptyspawn.so"

for f in libtermux.so libptyspawn.so; do
    "$STRIP" --strip-unneeded "$OUT/$f"
    echo "built $f"
done

# The drift check. libanland_consumer.so once shipped without a symbol its
# Java side declared, and the app crashed on first use; verify by name here
# instead of on the device.
missing=0
for sym in JNI_createSubprocess JNI_setPtyWindowSize JNI_waitFor JNI_close XiaoianPty_openPty; do
    if "$NM" -D --defined-only "$OUT/libtermux.so" | grep -q " Java_com_termux_terminal_$sym\$"; then
        echo "  ok  Java_com_termux_terminal_$sym"
    else
        echo "  MISSING  Java_com_termux_terminal_$sym" >&2
        missing=1
    fi
done
[ "$missing" -eq 0 ] || { echo "JNI symbols missing from libtermux.so" >&2; exit 1; }
