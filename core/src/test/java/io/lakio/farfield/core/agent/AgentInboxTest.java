package io.lakio.farfield.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentInboxTest {

    @TempDir
    Path dir;

    @Test
    void openDropsTheFilesOfAnEarlierRun() throws IOException {
        Files.createDirectories(dir.resolve(AgentInbox.INBOX));
        Files.writeString(dir.resolve(AgentInbox.INBOX).resolve("old.cmd"), "quit\n");
        Files.writeString(dir.resolve(AgentInbox.OUTBOX), "{\"old\":true}\n");
        AgentInbox inbox = AgentInbox.open(dir);
        assertEquals(1, inbox.staleFiles());
        assertTrue(inbox.poll().isEmpty());
        assertEquals("", Files.readString(dir.resolve(AgentInbox.OUTBOX)));
        assertTrue(Files.isDirectory(inbox.shots()));
    }

    @Test
    void readsCommandFilesInNameOrderAndDeletesThem() throws IOException {
        AgentInbox inbox = AgentInbox.open(dir);
        Path in = dir.resolve(AgentInbox.INBOX);
        Files.writeString(in.resolve("002.cmd"), "state\n");
        Files.writeString(in.resolve("001.cmd"), "# setup\ntp 0 120 0\n\nfly\n");
        Files.writeString(in.resolve("003.tmp"), "quit\n"); // still being written
        List<AgentInbox.Entry> entries = inbox.poll();
        assertEquals(3, entries.size());
        assertEquals("001.cmd", entries.get(0).file());
        assertEquals(2, entries.get(0).line());
        assertTrue(entries.get(0).command() instanceof AgentCommand.Teleport);
        assertNull(entries.get(1).command());
        assertTrue(entries.get(1).error().startsWith("unknown command"));
        assertEquals(4, entries.get(1).line());
        assertTrue(entries.get(2).command() instanceof AgentCommand.State);
        assertFalse(Files.exists(in.resolve("001.cmd")));
        assertTrue(Files.exists(in.resolve("003.tmp")));
        assertTrue(inbox.poll().isEmpty());
    }

    @Test
    void repliesAreOneJsonLineEach() throws IOException {
        AgentInbox inbox = AgentInbox.open(dir);
        Files.writeString(dir.resolve(AgentInbox.INBOX).resolve("a.cmd"), "command say \"hi\"\\\n");
        AgentInbox.Entry entry = inbox.poll().get(0);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tick", 12L);
        fields.put("fps", 59.5);
        fields.put("nan", Double.NaN);
        fields.put("lod", Map.of("meshing", 0));
        fields.put("none", Map.of());
        inbox.reply(entry, true, fields);
        inbox.reply(entry, false, Map.of("error", "line\nbreak"));
        List<String> lines = Files.readAllLines(dir.resolve(AgentInbox.OUTBOX), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("{\"file\":\"a.cmd\",\"line\":1,\"cmd\":\"command say \\\"hi\\\"\\\\\",\"ok\":true,"
                + "\"tick\":12,\"fps\":59.5,\"nan\":null,\"lod\":{\"meshing\":0},\"none\":{}}", lines.get(0));
        assertTrue(lines.get(1).endsWith("\"ok\":false,\"error\":\"line\\nbreak\"}"));
    }
}
