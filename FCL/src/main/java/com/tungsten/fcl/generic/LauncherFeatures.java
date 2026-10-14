package com.tungsten.fcl.generic;

import com.tungsten.fcl.BuildConfig;

/** Build-time isolation for the retained Minecraft UI, services and network stack. */
public final class LauncherFeatures {
    private LauncherFeatures() {
    }

    public static boolean minecraftEnabled() {
        return BuildConfig.MINECRAFT_FEATURES;
    }

    public static void requireMinecraft() {
        if (!minecraftEnabled()) {
            throw new IllegalStateException("Minecraft features are disabled in PVZCE Launcher");
        }
    }
}
