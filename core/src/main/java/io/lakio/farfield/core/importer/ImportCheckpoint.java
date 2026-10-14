package io.lakio.farfield.core.importer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only record of the regions an import has completed, one line per
 * region: {@code <file name> <size> <last modified millis>}. A region counts
 * as done only if its file still has the same size and modification time, so
 * regions the game rewrote since are imported again.
 *
 * <p>Each line is written and flushed to disk when its region completes; a
 * crash can at worst leave a truncated last line, which loading ignores.
 */
public final class ImportCheckpoint implements AutoCloseable {

    static final String HEADER = "# farfield import checkpoint v1";

    private final Map<String, String> done = new HashMap<>();
    private final FileChannel out;

    private ImportCheckpoint(FileChannel out) {
        this.out = out;
    }

    public static ImportCheckpoint open(Path file) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ");
                if (parts.length == 3) {
                    entries.put(parts[0], line);
                }
            }
        } catch (NoSuchFileException e) {
            // First run.
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean fresh = !Files.exists(file) || Files.size(file) == 0;
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        ImportCheckpoint checkpoint = new ImportCheckpoint(channel);
        checkpoint.done.putAll(entries);
        if (fresh) {
            checkpoint.write(HEADER + "\n");
        } else if (!endsWithNewline(file)) {
            // Terminate a line truncated by a crash so the next entry starts clean.
            checkpoint.write("\n");
        }
        return checkpoint;
    }

    private static boolean endsWithNewline(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size == 0) {
                return true;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            ch.read(last, size - 1);
            return last.get(0) == '\n';
        }
    }

    public synchronized boolean isDone(Path region) throws IOException {
        String line = done.get(region.getFileName().toString());
        return line != null && line.equals(entry(region));
    }

    public synchronized void markDone(Path region) throws IOException {
        String line = entry(region);
        done.put(region.getFileName().toString(), line);
        write(line + "\n");
    }

    public synchronized int size() {
        return done.size();
    }

    private static String entry(Path region) throws IOException {
        return region.getFileName() + " " + Files.size(region) + " " + Files.getLastModifiedTime(region).toMillis();
    }

    private void write(String text) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) {
            out.write(bytes);
        }
        out.force(false);
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
