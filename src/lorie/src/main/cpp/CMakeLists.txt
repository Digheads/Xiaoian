cmake_minimum_required(VERSION 3.22)
project(lorie)

set(can_use_assembler TRUE)
enable_language(ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_BUILD_TYPE RelWithDebInfo)

include(TestBigEndian)
include(CheckIncludeFile)
include(CheckSourceCompiles)
include(CheckCCompilerFlag)
include(CheckSymbolExists)
include(CheckFunctionExists)
include(CheckTypeSize)

find_package(Python3 REQUIRED)
find_package(BISON REQUIRED)

# A compiler for the machine doing the build, for the generators that have to
# run here rather than on the phone (see recipes/host_tool.cmake). Upstream
# hardcoded /usr/bin/gcc; on Windows there is usually only MSVC, found through
# Visual Studio's own vswhere.
if(NOT DEFINED HOST_CC)
    # Visual Studio first on Windows: a `clang` on PATH there is usually the
    # NDK's, which only builds for Android and has no host headers.
    if(CMAKE_HOST_WIN32)
        find_program(VSWHERE_PROGRAM vswhere
                PATHS "$ENV{ProgramFiles\(x86\)}/Microsoft Visual Studio/Installer")
        if(VSWHERE_PROGRAM)
            execute_process(COMMAND "${VSWHERE_PROGRAM}" -latest -products * -property installationPath
                    OUTPUT_VARIABLE VS_PATH OUTPUT_STRIP_TRAILING_WHITESPACE)
            if(EXISTS "${VS_PATH}/VC/Auxiliary/Build/vcvars64.bat")
                set(HOST_CC "MSVC")
                set(HOST_VCVARS "${VS_PATH}/VC/Auxiliary/Build/vcvars64.bat")
            endif()
        endif()
    endif()
    if(NOT HOST_CC)
        find_program(HOST_CC_PROGRAM NAMES gcc cc clang)
        set(HOST_CC "${HOST_CC_PROGRAM}")
    endif()
endif()
if(NOT HOST_CC)
    message(FATAL_ERROR "No host C compiler found (gcc, cc, clang or Visual Studio); set -DHOST_CC=")
endif()
message(STATUS "Host compiler for build-time generators: ${HOST_CC}")

# Apply a patch once, and stay quiet when it is already in.
#
# This used to go through `bash -c "... || ..."`, which on Windows found
# something else called bash (WSL) and split the paths on their spaces, so
# every patch was silently skipped -- and a skipped libepoxy.patch means no
# GL/gl.h and a build that stops halfway. Now `patch` is called directly, and
# a real failure is an error rather than a shrug.
# Windows filenames are case-insensitive, so bionic's `#include <xlocale.h>`
# from locale.h finds libx11's X11/Xlocale.h instead -- which includes
# locale.h right back, leaving locale_t undefined and every libx11 file
# failing. A copy of bionic's xlocale.h, first in the search path, ends that.
if(CMAKE_HOST_WIN32)
    file(WRITE "${CMAKE_BINARY_DIR}/case-shim/xlocale.h"
            "#pragma once\n#include <sys/cdefs.h>\nstruct __locale_t;\ntypedef struct __locale_t* locale_t;\n")
    include_directories(BEFORE "${CMAKE_BINARY_DIR}/case-shim")
endif()

find_program(PATCH_PROGRAM NAMES patch
        HINTS "$ENV{ProgramFiles}/Git/usr/bin" "$ENV{ProgramW6432}/Git/usr/bin" REQUIRED)

function(target_apply_patch patch_target d p)
    # Reverse dry-run succeeds only if the patch is already in.
    execute_process(COMMAND "${PATCH_PROGRAM}" -p1 -R --dry-run -d "${d}" -i "${p}"
            RESULT_VARIABLE applied OUTPUT_QUIET ERROR_QUIET)
    if(applied EQUAL 0)
        return()
    endif()
    execute_process(COMMAND "${PATCH_PROGRAM}" -p1 -N -d "${d}" -i "${p}" -r -
            RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE out)
    if(NOT rc EQUAL 0)
        message(FATAL_ERROR "Applying ${p} to ${d} failed:\n${out}")
    endif()
    message(STATUS "Applied ${p}")
endfunction()

set(test_compile_options
        "-Wall"
        "-Wpointer-arith"
        "-Wmissing-declarations"
        "-Wformat=2"
        "-Winvalid-pch"
        "-Wstrict-prototypes"
        "-Wmissing-prototypes"
        "-Wnested-externs"
        "-Wbad-function-cast"
        "-Wold-style-definition"
        "-Wunused"
        "-Wuninitialized"
        "-Wshadow"
        "-Wmissing-noreturn"
        "-Wmissing-format-attribute"
        "-Wredundant-decls"
        "-Wlogical-op"
        "-Werror=implicit"
        "-Werror=nonnull"
        "-Werror=init-self"
        "-Werror=main"
        "-Werror=missing-braces"
        "-Werror=sequence-point"
        "-Werror=return-type"
        "-Werror=trigraphs"
        "-Werror=array-bounds"
        "-Werror=write-strings"
        "-Werror=address"
        "-Werror=int-to-pointer-cast"
        "-Werror=pointer-to-int-cast"
        "-fno-strict-aliasing"
        "-Wno-unused-but-set-variable"
        "-Wno-unused-parameter"
        "-Wno-unused-variable"
        "-Wno-unused-function"
        "-Wno-missing-prototypes"
        "-Wno-macro-redefined"
        "-Wno-uninitialized"
        "-Wno-shadow")

set(CMAKE_REQUIRED_QUIET_OLD ${CMAKE_REQUIRED_QUIET})
set(CMAKE_REQUIRED_QUIET 1)
set(common_compile_options)
set(I 0)
foreach (option ${test_compile_options})
    check_c_compiler_flag(${option} COMPILER_TEST_${I})
    if (COMPILER_TEST_${I})
        set(common_compile_options ${common_compile_options} ${option})
    endif()
    MATH(EXPR I "${I}+1")
endforeach ()
set(CMAKE_REQUIRED_QUIET ${CMAKE_REQUIRED_QUIET_OLD})

foreach(project xorgproto fontenc md pixman tirpc xshmfence Xdmcp Xau Xfont2 xkbcomp xserver)
    include(recipes/${project}.cmake)
endforeach()
