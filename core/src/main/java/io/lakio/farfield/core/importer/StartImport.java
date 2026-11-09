package io.lakio.farfield.core.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which region files a server imports into its LODs at start (ADR-124): a
 * dimension never imported (no checkpoint) is read whole; after a clean
 * stop, only the regions changed since that stop, while Farfield was not
 * there (removed for a while, the world edited outside the game); after a
 * crash, every region the checkpoint does not hold as unchanged.
 *
 * <p>The stop mark is one line in the world: {@code stopped <millis>} written
 * at a clean stop, {@code running <millis>} at start, so a crash leaves
 * {@code running}.
 */
public final class StartImport {

    /** Region times are compared with this slack: a file written during the stop itself is not "changed after". */
    public static final long SLACK_MILLIS = 5_000;

    /** The mark's state: when the server last stopped cleanly, or that it did not. */
    public record Mark(boolean clean, long millis) {
    }

    private StartImport() {
    }

    /**
     * The regions to import, in the given order.
     *
     * @param regions        the dimension's region files, nearest the spawn first
     * @param imported       whether the dimension has an import checkpoint
     * @param mark           the stop mark read at start, null when there is none
     */
    public static List<Path> select(List<Path> regions, boolean imported, Mark mark) throws IOException {
        if (!imported || mark == null || !mark.clean()) {
            return regions; // never imported, or no clean stop to compare with: the checkpoint sorts them
        }
        List<Path> changed = new ArrayList<>();
        for (Path region : regions) {
            if (Files.getLastModifiedTime(region).toMillis() > mark.millis() + SLACK_MILLIS) {
                changed.add(region);
            }
        }
        return changed;
    }

    /** Reads the mark; null when there is none or it does not read. */
    public static Mark read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        String[] w = Files.readString(file, StandardCharsets.UTF_8).strip().split("\\s+");
        if (w.length != 2) {
            return null;
        }
        try {
            long millis = Long.parseLong(w[1]);
            return switch (w[0].toLowerCase(Locale.ROOT)) {
                case "stopped" -> new Mark(true, millis);
                case "running" -> new Mark(false, millis);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Writes {@code stopped <millis>} (a clean stop) or {@code running <millis>} (at start). */
    public static void write(Path file, boolean stopped, long millis) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(tmp, (stopped ? "stopped " : "running ") + millis + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
