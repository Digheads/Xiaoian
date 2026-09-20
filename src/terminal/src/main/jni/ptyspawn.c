/*
 * ptyspawn -- attach a command to an already-created pty slave, as its
 * controlling terminal, and exec it.
 *
 * Why this exists. The vendored termux.c creates the pty *and* forks the child
 * itself, so the child inherits the app's uid. Running anything as root that
 * way means exec'ing `su` per session, and every `su` invocation is another
 * Magisk prompt or toast -- the exact thing RootShell was written to stop. So
 * the work is split: the app opens /dev/ptmx with its own uid (allowed for app
 * uids under Enforcing SELinux, measured on device), and hands the slave's
 * path to the one already-open root shell, which runs this helper on it. Zero
 * additional su invocations.
 *
 * Doing that from shell alone does not work: a plain `exec < /dev/pts/N` in a
 * non-session-leader gives you the fds but no controlling terminal, so there
 * is no job control, no foreground process group, and Ctrl-C sends nothing.
 * setsid() + TIOCSCTTY is what makes it a real terminal.
 *
 * Side effect the session layer relies on: setsid() makes this process a
 * session and process group leader, so its pid is also its sid and pgid. The
 * caller can therefore tear the whole session down with kill(-pid) and does
 * not need to write .sid marker files.
 *
 * Built as lib*.so and packaged with useLegacyPackaging so the installer
 * extracts it into nativeLibraryDir with the execute bit -- /data/data is
 * non-executable.
 *
 * usage: libptyspawn.so /dev/pts/N <command> [args...]
 */

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int fail(int fd, const char *what)
{
    /* Once the pty is open this reaches the terminal the user is looking at;
     * before that, stderr goes back up the root shell and into the log. */
    dprintf(fd, "ptyspawn: %s: %s\r\n", what, strerror(errno));
    return 1;
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        dprintf(2, "usage: %s /dev/pts/N <command> [args...]\n", argv[0]);
        return 2;
    }

    /* New session: no controlling terminal, and we are the leader, which is
     * what TIOCSCTTY below requires. */
    if (setsid() == (pid_t) -1)
        return fail(2, "setsid");

    /* Deliberately no O_NOCTTY: this is meant to become our terminal. */
    int fd = open(argv[1], O_RDWR);
    if (fd < 0)
        return fail(2, argv[1]);

    if (ioctl(fd, TIOCSCTTY, 0) < 0)
        return fail(fd, "TIOCSCTTY");

    if (dup2(fd, 0) < 0 || dup2(fd, 1) < 0 || dup2(fd, 2) < 0)
        return fail(fd, "dup2");
    if (fd > 2)
        close(fd);

    execvp(argv[2], argv + 2);
    return fail(2, argv[2]);
}
