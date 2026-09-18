#!/bin/sh
# Builds the three native artefacts of the anland module from the sources in
# src/main/jni into src/main/jniLibs/arm64-v8a.
#
# Why this exists instead of externalNativeBuild: the module's build.gradle has
# no `externalNativeBuild` block, so src/main/jni/CMakeLists.txt is never run
# and the .so files under jniLibs are shipped as prebuilts. They then drift
# from the sources beside them -- libanland_consumer.so was once missing
# Java_com_anland_termux_Native_nativeSetAudioKeepalive while Native.java
# declared it, which crashed MainActivity on the first surfaceChanged. Run this
# after touching anything under src/main/jni.
#
# The flags mirror CMakeLists.txt exactly. API 30 is not negotiable: the
# display consumer calls memfd_create(), which bionic only declares from 30 on.
set -eu

NDK="${ANDROID_NDK:-C:/Program Files (x86)/Android/android-sdk/ndk/29.0.14206865}"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
CC="$BIN/aarch64-linux-android30-clang"
STRIP="$BIN/llvm-strip"

cd "$(dirname "$0")/src/main/jni"
CORE=anland_core
OUT=../jniLibs/arm64-v8a
mkdir -p "$OUT"

# The JNI library behind MainActivity, Native and CameraServices.
"$CC" -O3 -flto -fPIC -shared -fuse-ld=lld \
    -I "$CORE/libdisplay_consumer" -I "$CORE/common" \
    native_consumer.c native_audio.c camera_service.c \
    "$CORE/libdisplay_consumer/display_consumer.c" \
    "$CORE/common/socket_utils.c" \
    -landroid -llog -ldl -laaudio -lcamera2ndk -lmediandk \
    -o "$OUT/libanland_consumer.so"

# Two executables, named lib*.so so the packager extracts them into
# nativeLibraryDir with the execute bit -- /data/data is non-executable, and
# that extraction only happens with useLegacyPackaging = true.
"$CC" -O2 -Wall -fPIE -pie -I "$CORE/common" \
    fd_helper.c "$CORE/common/socket_utils.c" \
    -llog -o "$OUT/libfdhelper.so"

"$CC" -O2 -Wall -fPIE -pie -I "$CORE/common" \
    daemon/anland.c "$CORE/common/socket_utils.c" \
    -llog -o "$OUT/libanland.so"

for f in libanland_consumer.so libfdhelper.so libanland.so; do
    "$STRIP" --strip-unneeded "$OUT/$f"
    echo "built $f"
done
