package com.tungsten.fcl.generic;

import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps file operations exclusive across Activity recreation, without retaining an Activity. */
public final class GameOperationGate {
    private static volatile Object owner;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private GameOperationGate() {
    }

    public static boolean isBusy() {
        return owner != null;
    }

    public static boolean isOwnedBy(Object token) {
        return owner == token;
    }

    public static boolean begin(Object token) {
        synchronized (GameOperationGate.class) {
            if (owner == token) return true;
            if (owner != null) return false;
            owner = token;
        }
        listeners.forEach(Runnable::run);
        return true;
    }

    public static void end(Object token) {
        synchronized (GameOperationGate.class) {
            if (owner != token) return;
            owner = null;
        }
        listeners.forEach(Runnable::run);
    }

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }
}
