/*
 * Not upstream Termux. Opens a pty without forking anything into it.
 *
 * termux.c couples pty creation to fork+exec, and the child it forks inherits
 * the app's uid. Everything Xiaoian runs in a terminal is root, so using it
 * would mean exec'ing `su` once per session -- one Magisk prompt or toast each
 * time, which is exactly what RootShell exists to avoid. Instead the app opens
 * the pty here with its own uid (an app uid can open /dev/ptmx under Enforcing
 * SELinux -- measured on device) and hands the slave path to the one root
 * shell that is already open, which runs libptyspawn.so on it.
 *
 * The termios and winsize setup below is copied from termux.c's
 * create_subprocess so an adopted pty behaves identically to one Termux made.
 */

#include <fcntl.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

#define UNUSED(x) x __attribute__((__unused__))

static jstring throw_runtime_exception(JNIEnv *env, char const *message) {
    jclass ex_class = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, ex_class, message);
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_termux_terminal_XiaoianPty_openPty(JNIEnv *env, jclass UNUSED(clazz),
                                            jintArray master_fd_out,
                                            jint rows, jint columns,
                                            jint cell_width, jint cell_height) {
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname))) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    /* UTF-8 on, flow control off -- otherwise Ctrl+S freezes the display. */
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    struct winsize sz = { .ws_row = (unsigned short) rows,
                          .ws_col = (unsigned short) columns,
                          .ws_xpixel = (unsigned short) (columns * cell_width),
                          .ws_ypixel = (unsigned short) (rows * cell_height) };
    ioctl(ptm, TIOCSWINSZ, &sz);

    jint fd = ptm;
    (*env)->SetIntArrayRegion(env, master_fd_out, 0, 1, &fd);
    return (*env)->NewStringUTF(env, devname);
}
