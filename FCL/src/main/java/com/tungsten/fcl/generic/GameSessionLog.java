package com.tungsten.fcl.generic;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Full session file plus a bounded display tail and one outstanding UI refresh. */
public final class GameSessionLog implements Closeable {
    public static final int DISPLAY_LIMIT = 16000;
    private final File file;
    private final StringBuilder tail = new StringBuilder(DISPLAY_LIMIT);
    private BufferedWriter writer;
    private boolean fileUnavailable;
    private boolean refreshPending;
    private boolean closed;

    public GameSessionLog(File file) {
        this.file = file;
    }

    /** Returns true only when the caller needs to schedule a display refresh. */
    public synchronized boolean append(String text) {
        if (closed) return false;
        if (!fileUnavailable) {
            try {
                if (writer == null) writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
                writer.write(text);
                writer.flush();
            } catch (IOException ignore) {
                // Keep displaying logs if file output fails; do not retry an open on every line.
                closeWriter();
                fileUnavailable = true;
            }
        }
        if (text.length() >= DISPLAY_LIMIT) {
            tail.setLength(0);
            tail.append(text, text.length() - DISPLAY_LIMIT, text.length());
        } else {
            int excess = tail.length() + text.length() - DISPLAY_LIMIT;
            if (excess > 0) tail.delete(0, excess);
            tail.append(text);
        }
        if (tail.length() > 0 && Character.isLowSurrogate(tail.charAt(0))) tail.deleteCharAt(0);
        boolean schedule = !refreshPending;
        refreshPending = true;
        return schedule;
    }

    public synchronized String snapshot() {
        refreshPending = false;
        return tail.toString();
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignore) {
                // The on-screen tail is still available.
            }
            writer = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeWriter();
    }
}
