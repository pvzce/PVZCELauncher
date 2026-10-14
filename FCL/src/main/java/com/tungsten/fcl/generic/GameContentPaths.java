package com.tungsten.fcl.generic;

import java.io.File;
import java.io.IOException;

/** SAF display names are single path components, never paths supplied by a provider. */
public final class GameContentPaths {
    private GameContentPaths() {
    }

    public static File child(File parent, String name) throws IOException {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            throw new IOException("Invalid filename");
        }
        File result = new File(parent, name).getCanonicalFile();
        if (!parent.getCanonicalFile().equals(result.getParentFile())) throw new IOException("File escapes destination");
        return result;
    }
}
