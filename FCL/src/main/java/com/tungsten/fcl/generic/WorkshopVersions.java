package com.tungsten.fcl.generic;

import com.google.gson.JsonElement;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarFile;

/** Conservative site range subset and a non-executing read of the game's version constant. */
public final class WorkshopVersions {
    private WorkshopVersions() {}

    public static boolean matches(String version, JsonElement range) throws IOException {
        if (range.isJsonArray()) {
            boolean match = false;
            for (JsonElement option : range.getAsJsonArray()) match |= matches(version, option);
            return match;
        }
        if (!range.isJsonPrimitive() || !range.getAsJsonPrimitive().isString())
            throw new IOException("不支持的模组依赖范围");
        String expression = range.getAsString().trim();
        if (expression.equals("*")) return true;
        boolean result = true;
        for (String part : expression.split("\\s+")) result &= part(version, part);
        return result;
    }

    private static boolean part(String version, String expression) throws IOException {
        if (version.equals(expression)) return true;
        String operator = "=";
        for (String op : new String[] {">=", "<=", ">", "<", "=", "~", "^"}) {
            if (expression.startsWith(op)) {
                operator = op;
                expression = expression.substring(op.length());
                break;
            }
        }
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || !expression.matches("[0-9xX*]+(\\.[0-9xX*]+){0,2}")) {
            throw new IOException("依赖范围需要游戏加载器进一步判断：" + expression);
        }
        int[] actual = numbers(version);
        String[] components = expression.split("\\.");
        boolean wildcard = expression.matches(".*[xX*].*") || components.length < 3;
        int[] expected = numbers(expression);
        if (wildcard) {
            if (!operator.equals("=")) throw new IOException("不支持的通配比较范围");
            for (int i = 0; i < components.length; i++) {
                if (components[i].equalsIgnoreCase("x") || components[i].equals("*")) return true;
                if (actual[i] != expected[i]) return false;
            }
            return true;
        }
        int compare = compare(actual, expected);
        switch (operator) {
            case ">=":
                return compare >= 0;
            case "<=":
                return compare <= 0;
            case ">":
                return compare > 0;
            case "<":
                return compare < 0;
            case "~":
                return compare >= 0 && actual[0] == expected[0] && actual[1] == expected[1];
            case "^":
                int boundary = expected[0] != 0 ? 0 : expected[1] != 0 ? 1 : 2;
                for (int i = 0; i <= boundary; i++) if (actual[i] != expected[i]) return false;
                return compare >= 0;
            default:
                return compare == 0;
        }
    }

    private static int[] numbers(String version) throws IOException {
        String[] parts = version.split("\\.");
        int[] result = new int[3];
        try {
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equalsIgnoreCase("x") || parts[i].equals("*")) break;
                result[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException error) {
            throw new IOException("依赖版本无效", error);
        }
        return result;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        return 0;
    }

    public static String gameVersion(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            var entry = jar.getJarEntry("com/pvzce/launcher/PvzceVersions.class");
            if (entry == null) throw new IOException("游戏 JAR 缺少 PvzceVersions，请先导入完整游戏");
            try (InputStream input = jar.getInputStream(entry);
                    DataInputStream data =
                            new DataInputStream(
                                    new java.io.ByteArrayInputStream(
                                            WorkshopApi.read(input, 1024 * 1024)))) {
                if (data.readInt() != 0xCAFEBABE) throw new IOException("版本类格式无效");
                data.readInt();
                Object[] pool = new Object[data.readUnsignedShort()];
                for (int i = 1; i < pool.length; i++) {
                    switch (data.readUnsignedByte()) {
                        case 1:
                            pool[i] = data.readUTF();
                            break;
                        case 8:
                            pool[i] = data.readUnsignedShort();
                            break;
                        case 3:
                        case 4:
                        case 9:
                        case 10:
                        case 11:
                        case 12:
                        case 17:
                        case 18:
                            data.readInt();
                            break;
                        case 5:
                        case 6:
                            data.readLong();
                            i++;
                            break;
                        case 7:
                        case 16:
                        case 19:
                        case 20:
                            data.readUnsignedShort();
                            break;
                        case 15:
                            data.readUnsignedByte();
                            data.readUnsignedShort();
                            break;
                        default:
                            throw new IOException("版本类常量池无效");
                    }
                }
                data.readUnsignedShort();
                data.readUnsignedShort();
                data.readUnsignedShort();
                int interfaces = data.readUnsignedShort();
                for (int i = 0; i < interfaces; i++) data.readUnsignedShort();
                int fields = data.readUnsignedShort();
                for (int i = 0; i < fields; i++) {
                    data.readUnsignedShort();
                    String name = (String) pool[data.readUnsignedShort()];
                    data.readUnsignedShort();
                    int attributes = data.readUnsignedShort();
                    for (int j = 0; j < attributes; j++) {
                        String attribute = (String) pool[data.readUnsignedShort()];
                        int size = data.readInt();
                        if (size < 0 || size > 1024 * 1024) throw new IOException("版本类属性过大");
                        if (name.equals("GAME_VERSION")
                                && attribute.equals("ConstantValue")
                                && size == 2) {
                            Object value = pool[data.readUnsignedShort()];
                            if (value instanceof Integer && pool[(Integer) value] instanceof String)
                                return (String) pool[(Integer) value];
                        } else {
                            byte[] skipped = new byte[size];
                            data.readFully(skipped);
                        }
                    }
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("无法读取游戏版本", error);
        }
        throw new IOException("游戏 JAR 没有可读取的 GAME_VERSION 常量");
    }
}
