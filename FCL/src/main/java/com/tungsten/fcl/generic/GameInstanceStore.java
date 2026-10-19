package com.tungsten.fcl.generic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Persistent independent launch configurations. Archiving never deletes game data. */
public final class GameInstanceStore {
    private static final Object LOCK = new Object();
    private final File registry;
    private final File games;

    public static final class Instance {
        public final String id;
        public final String name;
        public final boolean archived;
        public final Map<String, String> config;

        private Instance(String id, String name, boolean archived, Map<String, String> config) {
            this.id = id;
            this.name = name;
            this.archived = archived;
            this.config = Collections.unmodifiableMap(config);
        }

        public File directory() {
            return new File(config.get("work"));
        }
    }

    public GameInstanceStore(File files) throws IOException {
        registry = new File(files.getCanonicalFile(), "launcher_instances/registry.properties");
        games = new File(files.getCanonicalFile(), "pvzce_instances");
    }

    /** One-time adoption of the old directory and preferences; the originals are untouched. */
    public Instance initialize(Map<String, String> legacy) throws IOException {
        synchronized (LOCK) {
            if (registry.exists()) return selected();
            Map<String, String> config = new LinkedHashMap<>(legacy);
            if (!config.containsKey("work")) throw new IOException("Missing legacy game directory");
            Properties state = new Properties();
            state.setProperty("format", "1");
            String id = UUID.randomUUID().toString();
            config.put("work", checkedDirectory(state, id, config.get("work")).getPath());
            put(state, id, "默认实例", false, config);
            state.setProperty("order", id);
            state.setProperty("selected", id);
            persist(state);
            return read(state, id);
        }
    }

    public List<Instance> list(boolean archived) throws IOException {
        synchronized (LOCK) {
            Properties state = load();
            List<Instance> result = new ArrayList<>();
            for (String id : ids(state)) {
                Instance item = read(state, id);
                if (item.archived == archived) result.add(item);
            }
            return result;
        }
    }

    public Instance selected() throws IOException {
        synchronized (LOCK) {
            Properties state = load();
            Instance item = read(state, state.getProperty("selected", ""));
            if (item.archived) throw new IOException("Selected instance is archived");
            return item;
        }
    }

    public Instance get(String id) throws IOException {
        synchronized (LOCK) {
            return read(load(), id);
        }
    }

    public Instance create(String name, Map<String, String> template) throws IOException {
        synchronized (LOCK) {
            Properties state = load();
            String id = UUID.randomUUID().toString();
            Map<String, String> config = new LinkedHashMap<>(template);
            File directory = checkedDirectory(state, id, new File(games, id).getPath());
            config.put("work", directory.getPath());
            // JAR and runtime libraries can be shared; all mutable game data starts empty.
            Files.createDirectories(directory.toPath());
            for (String child :
                    new String[] {"mods", "resourcepacks", "datapacks", "saves", "config"}) {
                Files.createDirectories(new File(directory, child).toPath());
            }
            put(state, id, validName(name), false, config);
            state.setProperty("order", state.getProperty("order") + "," + id);
            state.setProperty("selected", id);
            persist(state);
            return read(state, id);
        }
    }

    public void select(String id) throws IOException {
        synchronized (LOCK) {
            Properties state = load();
            if (read(state, id).archived)
                throw new IOException("Restore the instance before selecting it");
            state.setProperty("selected", id);
            persist(state);
        }
    }

    public void update(String id, Map<String, String> config) throws IOException {
        synchronized (LOCK) {
            Properties state = load();
            Instance old = read(state, id);
            Map<String, String> copy = new LinkedHashMap<>(config);
            copy.put("work", checkedDirectory(state, id, copy.get("work")).getPath());
            if (old.config.equals(copy)) return;
            put(state, id, old.name, old.archived, copy);
            persist(state);
        }
    }

    public void rename(String id, String name) throws IOException {
        synchronized (LOCK) {
            Properties state = load();
            read(state, id);
            state.setProperty(id + ".name", validName(name));
            persist(state);
        }
    }

    public void archive(String id, boolean archive) throws IOException {
        synchronized (LOCK) {
            Properties state = load();
            Instance item = read(state, id);
            if (archive && !item.archived) {
                String next = null;
                for (String candidate : ids(state)) {
                    if (!candidate.equals(id) && !read(state, candidate).archived) {
                        next = candidate;
                        break;
                    }
                }
                if (next == null) throw new IOException("至少保留一个可用实例");
                if (id.equals(state.getProperty("selected"))) state.setProperty("selected", next);
            }
            state.setProperty(id + ".archived", Boolean.toString(archive));
            persist(state);
        }
    }

    private File checkedDirectory(Properties state, String id, String path) throws IOException {
        if (path == null || !new File(path).isAbsolute()) throw new IOException("游戏目录必须为绝对路径");
        File directory = new File(path).getCanonicalFile();
        File metadata = registry.getParentFile();
        if (overlap(directory, metadata)) throw new IOException("游戏目录不能包含启动器配置目录");
        for (String other : ids(state)) {
            if (!other.equals(id)
                    && overlap(directory, read(state, other).directory().getCanonicalFile())) {
                throw new IOException("实例目录不能相同或互相包含（包括已归档实例）");
            }
        }
        return directory;
    }

    private static boolean overlap(File a, File b) {
        return a.toPath().startsWith(b.toPath()) || b.toPath().startsWith(a.toPath());
    }

    private static String validName(String name) throws IOException {
        if (name == null
                || name.trim().isEmpty()
                || name.length() > 100
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("实例名称须为 1–100 个字符");
        }
        return name.trim();
    }

    private Properties load() throws IOException {
        checkRegistryPath();
        Properties state = new Properties();
        try (InputStream input = Files.newInputStream(registry.toPath())) {
            state.load(input);
        }
        if (!"1".equals(state.getProperty("format")))
            throw new IOException("Unsupported instance registry format");
        return state;
    }

    private static List<String> ids(Properties state) {
        String order = state.getProperty("order", "");
        if (order.isEmpty()) return Collections.emptyList();
        return java.util.Arrays.asList(order.split(","));
    }

    private static Instance read(Properties state, String id) throws IOException {
        if (!ids(state).contains(id) || !state.containsKey(id + ".name"))
            throw new IOException("Unknown instance: " + id);
        Map<String, String> config = new LinkedHashMap<>();
        String prefix = id + ".config.";
        for (String key : state.stringPropertyNames()) {
            if (key.startsWith(prefix))
                config.put(key.substring(prefix.length()), state.getProperty(key));
        }
        if (!config.containsKey("work")) throw new IOException("Instance has no directory");
        return new Instance(
                id,
                state.getProperty(id + ".name"),
                Boolean.parseBoolean(state.getProperty(id + ".archived")),
                config);
    }

    private static void put(
            Properties state, String id, String name, boolean archived, Map<String, String> config)
            throws IOException {
        JavaLaunchSpec.rejectLoginArguments(JavaLaunchSpec.lines(config.getOrDefault("args", "")));
        String prefix = id + ".config.";
        for (String key : new ArrayList<>(state.stringPropertyNames())) {
            if (key.startsWith(prefix)) state.remove(key);
        }
        state.setProperty(id + ".name", name);
        state.setProperty(id + ".archived", Boolean.toString(archived));
        config.forEach((key, value) -> state.setProperty(prefix + key, value));
    }

    private void persist(Properties state) throws IOException {
        checkRegistryPath();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        state.store(bytes, "PVZCE launcher instances");
        InstanceFiles.write(registry, bytes.toByteArray());
    }

    private void checkRegistryPath() throws IOException {
        if (!registry.getParentFile()
                        .getCanonicalFile()
                        .equals(registry.getParentFile().getAbsoluteFile())
                || Files.isSymbolicLink(registry.toPath())
                || Files.isSymbolicLink(games.toPath())) {
            throw new IOException("实例配置或默认目录不能通过符号链接重定向");
        }
    }
}
