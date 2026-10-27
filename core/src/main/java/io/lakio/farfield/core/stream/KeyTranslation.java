package io.lakio.farfield.core.stream;

import io.lakio.farfield.core.anvil.KeyRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One key table of a server's store as a client received it ({@link
 * StreamProtocol.Keys}), and the id each of its keys has in the client's
 * mirror (ADR-068). The mirror's table is its own: the client may add keys
 * of its own (the preview's covered materials) without shifting the
 * server's, and records are translated on receipt. Not thread-safe for
 * writes; {@link #map} may be read from another thread once published.
 */
public final class KeyTranslation {

    private final KeyRegistry local;
    private final List<String> serverKeys = new ArrayList<>();
    private volatile int[] map = new int[0];

    public KeyTranslation(KeyRegistry local) {
        this.local = local;
    }

    /**
     * The keys of server ids {@code from} on. Keys already held must be the
     * same; new ones must follow the last held without a gap.
     *
     * @return false when they disagree with the keys held: the mirror no
     *         longer matches that server's table
     */
    public boolean add(int from, String[] keys) {
        int[] m = map;
        for (int i = 0; i < keys.length; i++) {
            int id = from + i;
            if (id < serverKeys.size()) {
                if (!serverKeys.get(id).equals(keys[i])) {
                    return false;
                }
                continue;
            }
            if (id > serverKeys.size()) {
                return false;
            }
            serverKeys.add(keys[i]);
            if (id >= m.length) {
                m = Arrays.copyOf(m, Math.max(64, id * 2));
            }
            m[id] = local.id(keys[i]);
        }
        map = m;
        return true;
    }

    /** Server keys held. */
    public int size() {
        return serverKeys.size();
    }

    /** This mirror's id of each server id below {@link #size}; do not modify. */
    public int[] map() {
        return map;
    }

    public void clear() {
        serverKeys.clear();
        map = new int[0];
    }

    /** Writes the server keys held, one per line. */
    public void write(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, serverKeys, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Reads keys {@link #write} wrote, after clearing; false when the file is missing or disagrees. */
    public boolean read(Path file) throws IOException {
        clear();
        if (!Files.exists(file)) {
            return false;
        }
        List<String> keys = Files.readAllLines(file, StandardCharsets.UTF_8);
        return add(0, keys.toArray(new String[0]));
    }
}
