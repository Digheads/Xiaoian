# Builds a small C program for the *host* and runs it, capturing its stdout.
#
# The X sources need a couple of generators that run where the build runs, not
# on the phone. Upstream simply called /usr/bin/gcc and redirected with `>`,
# which only works on Linux: Windows has no such path, and `>` inside an
# add_custom_command is not a shell redirect. Hence this script, run with
# `cmake -P`:
#
#   HOST_CC   the host compiler (gcc-style), or "MSVC" to use cl.exe
#   VCVARS    the MSVC environment script, when HOST_CC is MSVC
#   SRC       the .c file to build
#   BIN       where to put the host executable
#   WORKDIR   where to run it
#   ARGS      its arguments, semicolon-separated
#   OUT       the file its stdout goes to

if(HOST_CC STREQUAL "MSVC")
    # cl needs its environment (INCLUDE, LIB), so vcvars has to run first, in
    # the same shell. Through a .bat file rather than `cmd /c "..."`: the
    # quotes around the two paths do not survive the nesting. Its object file
    # lands next to the executable.
    get_filename_component(bin_dir "${BIN}" DIRECTORY)
    file(TO_NATIVE_PATH "${VCVARS}" vcvars_native)
    file(TO_NATIVE_PATH "${BIN}" bin_native)
    file(TO_NATIVE_PATH "${SRC}" src_native)
    set(bat "${bin_dir}/host_cc.bat")
    # /Fe: and the source in "..." form, and no /Fo at all: a quoted directory
    # ends in a backslash, which escapes the closing quote and takes the rest
    # of the line with it. The .obj lands in the working directory instead.
    file(WRITE "${bat}"
        "@echo off\r\n"
        "call \"${vcvars_native}\" >nul\r\n"
        "if errorlevel 1 exit /b 1\r\n"
        "cd /d \"${bin_dir}\"\r\n"
        "cl /nologo /Fe:\"${bin_native}\" \"${src_native}\"\r\n")
    execute_process(
        COMMAND cmd /c "${bat}"
        RESULT_VARIABLE rc
        OUTPUT_VARIABLE out
        ERROR_VARIABLE out)
else()
    execute_process(
        COMMAND "${HOST_CC}" -o "${BIN}" "${SRC}"
        RESULT_VARIABLE rc
        OUTPUT_VARIABLE out
        ERROR_VARIABLE out)
endif()

if(NOT rc EQUAL 0)
    message(FATAL_ERROR "host compile of ${SRC} failed:\n${out}")
endif()

execute_process(
    COMMAND "${BIN}" ${ARGS}
    WORKING_DIRECTORY "${WORKDIR}"
    OUTPUT_FILE "${OUT}"
    RESULT_VARIABLE rc
    ERROR_VARIABLE err)

if(NOT rc EQUAL 0)
    message(FATAL_ERROR "${BIN} failed:\n${err}")
endif()
