package io.lakio.farfield.core.world;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Names the player gave to remote worlds (ADR-022, ADR-035), for when the
 * automatic identity ({@link RemoteWorldId}: address and seed hash) guesses
 * wrong: a server that moved to another address, a proxy whose worlds share
 * a seed. A named world's LODs live in {@code <servers>/named/<name>/}
 * instead of {@code <servers>/<address>/<seed hash>/}. The names are kept in
 * {@code <servers>/identities.properties}, keyed {@code <address>/<seed hash>}.
 */
public final class RemoteWorldNames {

    public static final String FILE_NAME = "identities.properties";
    public static final String NAMED_FOLDER = "named";
    public static final int MAX_NAME_LENGTH = 64;

    private final Path serversDir;
    private final Properties names = new Properties();

    private RemoteWorldNames(Path serversDir) {
        this.serversDir = serversDir;
    }

    /** Reads the names under {@code serversDir}; none when the file does not exist. */
    public static RemoteWorldNames load(Path serversDir) throws IOException {
        RemoteWorldNames n = new RemoteWorldNames(serversDir);
        Path file = serversDir.resolve(FILE_NAME);
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                n.names.load(in);
            }
        }
        return n;
    }

    /** The name given to a world, or null when it uses its automatic folder. */
    public String nameOf(String addressFolder, String seedFolder) {
        return names.getProperty(key(addressFolder, seedFolder));
    }

    /** The folder of a world's LODs (its dimensions are folders below). */
    public Path worldDir(String addressFolder, String seedFolder) {
        String name = nameOf(addressFolder, seedFolder);
        return name != null ? namedDir(name) : automaticDir(addressFolder, seedFolder);
    }

    public Path automaticDir(String addressFolder, String seedFolder) {
        return serversDir.resolve(addressFolder).resolve(seedFolder);
    }

    public Path namedDir(String name) {
        return serversDir.resolve(NAMED_FOLDER).resolve(name);
    }

    /** Gives a world a name ({@link #sanitize}d by the caller), or removes it with null, and saves. */
    public void setName(String addressFolder, String seedFolder, String name) throws IOException {
        if (name == null) {
            names.remove(key(addressFolder, seedFolder));
        } else {
            names.setProperty(key(addressFolder, seedFolder), name);
        }
        save();
    }

    private void save() throws IOException {
        Files.createDirectories(serversDir);
        Path file = serversDir.resolve(FILE_NAME);
        Path tmp = serversDir.resolve(FILE_NAME + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            out.write("# Names given to remote worlds with /farfieldclient world name <name>:\n");
            out.write("# <address>/<seed hash> = <name>, LODs in named/<name>/.\n");
            for (var e : new TreeMap<>(names).entrySet()) {
                out.write(escape(e.getKey().toString()) + "=" + e.getValue() + "\n");
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String escape(String key) {
        return key.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=").replace(" ", "\\ ");
    }

    private static String key(String addressFolder, String seedFolder) {
        return addressFolder + "/" + seedFolder;
    }

    /**
     * A name as a folder name: lower case, letters, digits, {@code .},
     * {@code -} and {@code _}, other characters replaced by {@code _}, at
     * most {@link #MAX_NAME_LENGTH}; null when nothing usable is left.
     */
    public static String sanitize(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(Math.min(n.length(), MAX_NAME_LENGTH));
        for (int i = 0; i < n.length() && sb.length() < MAX_NAME_LENGTH; i++) {
            char c = n.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        String s = sb.toString();
        return s.isEmpty() || s.chars().allMatch(c -> c == '.') ? null : s;
    }

    /** What {@link #switchFolder} did. */
    public enum Switch {
        /** The current folder moved to the new one: the LODs follow the new identity. */
        MOVED,
        /** The new folder already existed and is used as is; the current one stays. */
        EXISTING,
        /** Neither existed: the new folder fills from now on. */
        EMPTY,
        /** The same folder: nothing to do. */
        SAME
    }

    /**
     * Prepares a change of identity from {@code from} to {@code to}: when
     * {@code to} does not exist and {@code from} does, {@code from} moves
     * there. The stores of {@code from} must be closed.
     */
    public static Switch switchFolder(Path from, Path to) throws IOException {
        if (from.toAbsolutePath().normalize().equals(to.toAbsolutePath().normalize())) {
            return Switch.SAME;
        }
        if (Files.exists(to)) {
            return Switch.EXISTING;
        }
        if (!Files.exists(from)) {
            return Switch.EMPTY;
        }
        Files.createDirectories(to.getParent());
        Files.move(from, to);
        // An address folder (or named/) left empty goes too.
        Path parent = from.getParent();
        if (parent != null && !parent.equals(to.getParent()) && Files.isDirectory(parent)) {
            try (var entries = Files.list(parent)) {
                if (entries.findAny().isEmpty()) {
                    Files.delete(parent);
                }
            }
        }
        return Switch.MOVED;
    }
}
