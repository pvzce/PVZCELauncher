package com.tungsten.fcl.generic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small atomic metadata writes; existing user files are never rewritten in place. */
public final class InstanceFiles {
    private InstanceFiles() {
    }

    public static void write(File file, byte[] bytes) throws IOException {
        Path parent = file.toPath().getParent();
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Refusing linked metadata: " + file);
        Path temporary = Files.createTempFile(parent, ".pvzce-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(bytes);
                output.getFD().sync();
            }
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
