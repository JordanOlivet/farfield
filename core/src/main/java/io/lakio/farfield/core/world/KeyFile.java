package io.lakio.farfield.core.world;

import io.lakio.farfield.core.anvil.KeyRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Persists a {@link KeyRegistry} as a UTF-8 text file, one key per line in id
 * order, so the ids stored in LOD records keep their meaning across sessions
 * (ADR-008). New keys are appended by {@link #persist()}, which the store
 * calls before writing any record that may use them.
 */
public final class KeyFile {

    private final Path file;
    private final KeyRegistry registry;
    private volatile int persisted;

    private KeyFile(Path file, KeyRegistry registry, int persisted) {
        this.file = file;
        this.registry = registry;
        this.persisted = persisted;
    }

    /** Loads the keys of {@code file} (if any) into a new registry, ids in line order. */
    public static KeyFile open(Path file) throws IOException {
        KeyRegistry registry = new KeyRegistry();
        int count = 0;
        if (Files.exists(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            int complete = text.lastIndexOf('\n') + 1;
            if (complete < text.length()) {
                // A torn last line after a crash: no record can use it (keys are
                // forced to disk before the records that use them), drop it.
                text = text.substring(0, complete);
                Files.writeString(file, text, StandardCharsets.UTF_8);
            }
            for (String line : text.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                int id = registry.id(line);
                if (id != count) {
                    throw new IOException("duplicate key '" + line + "' in " + file);
                }
                count++;
            }
        }
        return new KeyFile(file, registry, count);
    }

    public KeyRegistry registry() {
        return registry;
    }

    /** Appends the keys added since the last call and forces them to disk. */
    public synchronized void persist() throws IOException {
        int size = registry.size();
        if (size == persisted) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int id = persisted; id < size; id++) {
            sb.append(registry.key(id)).append('\n');
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer bytes = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        persisted = size;
    }

    /** Cheap check for the write path: true when keys wait to be persisted. */
    public boolean hasPending() {
        return registry.size() != persisted;
    }
}
