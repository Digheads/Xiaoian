cmake_minimum_required(VERSION 3.18)
project(termux C)

# The JNI library behind com.termux.terminal.JNI.
add_library(termux SHARED
    termux.c
    pty_open.c
)

target_compile_options(termux PRIVATE -O3)
target_link_libraries(termux log)

# The pty helper the app runs as a process of its own. Built as an executable
# but named lib*.so so the Android packager extracts it into the app's
# nativeLibraryDir with execute permission (requires useLegacyPackaging=true):
# /data/data itself is not executable.
add_executable(ptyspawn
    ptyspawn.c
)

set_target_properties(ptyspawn PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "ptyspawn"
    SUFFIX ".so"
)

target_compile_options(ptyspawn PRIVATE -O2)
