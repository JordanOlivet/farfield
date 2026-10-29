package io.lakio.farfield.core.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The files through which a test agent drives the dev client (ADR-095):
 * {@code <dir>/inbox/*.cmd} holds command files ({@link AgentCommand}, one
 * per line), read in name order and deleted once read; every command gets
 * one JSON line in {@code <dir>/outbox.jsonl}; screenshots go to
 * {@code <dir>/shots/}. An agent writes a file under another name first
 * (say {@code 001.tmp}) and renames it to {@code .cmd}, so a half-written
 * file is never read.
 */
public final class AgentInbox {

    public static final String INBOX = "inbox";
    public static final String OUTBOX = "outbox.jsonl";
    public static final String SHOTS = "shots";
    public static final String SUFFIX = ".cmd";

    /** A line of a command file: its command, or why it is none. */
    public record Entry(String file, int line, String text, AgentCommand command, String error) {
    }

    private final Path dir;
    private final Path inbox;
    private final Path outbox;
    private final int staleFiles;

    private AgentInbox(Path dir, int staleFiles) {
        this.dir = dir;
        this.inbox = dir.resolve(INBOX);
        this.outbox = dir.resolve(OUTBOX);
        this.staleFiles = staleFiles;
    }

    /**
     * Opens the folder for a new game: command files left by an earlier run
     * are deleted (they were meant for that run), the outbox starts empty.
     */
    public static AgentInbox open(Path dir) throws IOException {
        Path inbox = dir.resolve(INBOX);
        Files.createDirectories(inbox);
        Files.createDirectories(dir.resolve(SHOTS));
        int stale = 0;
        for (Path file : commandFiles(inbox)) {
            Files.deleteIfExists(file);
            stale++;
        }
        Files.writeString(dir.resolve(OUTBOX), "", StandardCharsets.UTF_8);
        return new AgentInbox(dir, stale);
    }

    public Path dir() {
        return dir;
    }

    public Path shots() {
        return dir.resolve(SHOTS);
    }

    /** Command files deleted at {@link #open}. */
    public int staleFiles() {
        return staleFiles;
    }

    /** The lines of the command files present now, in file name order; the files are deleted. */
    public List<Entry> poll() throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (Path file : commandFiles(inbox)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Files.delete(file);
            String name = file.getFileName().toString();
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i).strip();
                try {
                    AgentCommand command = AgentCommand.parse(text);
                    if (command != null) {
                        entries.add(new Entry(name, i + 1, text, command, null));
                    }
                } catch (IllegalArgumentException e) {
                    entries.add(new Entry(name, i + 1, text, null, e.getMessage()));
                }
            }
        }
        return entries;
    }

    /**
     * Appends the reply to a command: {@code file}, {@code line}, {@code cmd},
     * {@code ok}, then {@code fields} (strings, numbers, booleans, nested maps).
     */
    public void reply(Entry entry, boolean ok, Map<String, ?> fields) throws IOException {
        StringBuilder json = new StringBuilder(128).append('{');
        field(json, "file", entry.file());
        field(json, "line", entry.line());
        field(json, "cmd", entry.text());
        field(json, "ok", ok);
        for (Map.Entry<String, ?> f : fields.entrySet()) {
            field(json, f.getKey(), f.getValue());
        }
        json.setCharAt(json.length() - 1, '}');
        try (Writer out = Files.newBufferedWriter(outbox, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            out.write(json.append('\n').toString());
        }
    }

    private static List<Path> commandFiles(Path inbox) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inbox, "*" + SUFFIX)) {
            stream.forEach(files::add);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        files.sort(null);
        return files;
    }

    private static void field(StringBuilder json, String key, Object value) {
        string(json, key);
        json.append(':');
        value(json, value);
        json.append(',');
    }

    private static void value(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            json.append(value);
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            json.append(Double.isFinite(d) ? Double.toString(d) : "null");
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                json.append("{}");
                return;
            }
            json.append('{');
            for (Map.Entry<?, ?> e : map.entrySet()) {
                field(json, String.valueOf(e.getKey()), e.getValue());
            }
            json.setCharAt(json.length() - 1, '}');
        } else {
            string(json, value.toString());
        }
    }

    private static void string(StringBuilder json, String s) {
        json.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
