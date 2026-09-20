package com.termux.terminal;

/**
 * Not upstream Termux. Creates a pty and nothing else.
 *
 * {@link JNI#createSubprocess} makes the pty and forks the process into it in
 * one step, and that process inherits the app's uid. Everything Xiaoian runs in
 * a terminal is root, so going through it would mean exec'ing {@code su} per
 * session -- a Magisk prompt or toast each time. Splitting the two lets the app
 * make the pty with its own uid and have the already-open root shell put a root
 * process on the other end of it.
 *
 * Lives in this package so it can reuse the package-private {@link JNI} for the
 * operations that are identical either way, and rides in the same
 * {@code libtermux.so}.
 */
public final class XiaoianPty {

    static {
        System.loadLibrary("termux");
    }

    private XiaoianPty() {}

    /**
     * Opens {@code /dev/ptmx}, unlocks the slave and applies the same termios
     * and window size as {@link JNI#createSubprocess} would.
     *
     * @param masterFdOut one-element array the master file descriptor is written to
     * @return the slave's path, {@code /dev/pts/N}, to be handed to libptyspawn.so
     */
    public static native String openPty(int[] masterFdOut, int rows, int columns,
                                        int cellWidth, int cellHeight);

    /** @see JNI#setPtyWindowSize */
    public static void setWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight) {
        JNI.setPtyWindowSize(fd, rows, cols, cellWidth, cellHeight);
    }

    /** @see JNI#close */
    public static void close(int fd) {
        JNI.close(fd);
    }
}
