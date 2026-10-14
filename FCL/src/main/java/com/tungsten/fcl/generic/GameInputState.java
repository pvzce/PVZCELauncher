package com.tungsten.fcl.generic;

import java.util.HashSet;
import java.util.Set;

/** Held virtual keys and the text subset supported by the existing JNI char bridge. */
public final class GameInputState {
    public interface KeySink {
        void send(int key, boolean down);
    }

    private final KeySink sink;
    private final Set<Integer> held = new HashSet<>();

    public GameInputState(KeySink sink) {
        this.sink = sink;
    }

    public void press(int key) {
        if (held.add(key)) sink.send(key, true);
    }

    public void release(int key) {
        if (held.remove(key)) sink.send(key, false);
    }

    public void reset() {
        for (int key : held) sink.send(key, false);
        held.clear();
    }

    public static String checkedText(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isSurrogate(value.charAt(i)) || Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("This bridge supports BMP text (including Chinese), but not emoji/control characters");
            }
        }
        return value;
    }
}
