package com.tungsten.fcl.generic;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** A download refers to this snapshot, never to whichever instance a later screen selects. */
public final class WorkshopTarget {
    public final String id, name;
    public final File directory, jar;
    private final Map<String, String> configuration;

    public WorkshopTarget(GameInstanceStore registry, String id) throws IOException {
        GameInstanceStore.Instance instance = registry.get(id);
        if (instance.archived || !Boolean.parseBoolean(instance.config.get("pvzce")))
            throw new IOException("请选择未归档的 PVZCE 实例");
        this.id = id;
        name = instance.name;
        directory = instance.directory().getCanonicalFile();
        configuration = instance.config;
        String jarPath = configuration.getOrDefault("jar", "");
        if (jarPath.isBlank()) throw new IOException("目标实例尚未导入游戏 JAR");
        File configured = new File(jarPath);
        jar = configured.isAbsolute() ? configured : new File(directory, jarPath);
    }

    public void verify(GameInstanceStore registry) throws IOException {
        GameInstanceStore.Instance current = registry.get(id);
        if (current.archived
                || !current.directory().getCanonicalFile().equals(directory)
                || !current.config.equals(configuration)) {
            throw new IOException("目标实例已改变，取消安装");
        }
    }
}
