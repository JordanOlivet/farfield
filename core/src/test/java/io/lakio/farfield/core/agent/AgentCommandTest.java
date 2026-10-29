package io.lakio.farfield.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandTest {

    @Test
    void blankAndCommentLinesAreNoCommand() {
        assertNull(AgentCommand.parse("   "));
        assertNull(AgentCommand.parse("# fly north"));
    }

    @Test
    void chatCommandKeepsItsTextWithoutTheSlash() {
        assertEquals(new AgentCommand.Command("farfieldclient debug levels"),
                AgentCommand.parse("command /farfieldclient debug levels"));
        assertEquals(new AgentCommand.Command("time set noon"), AgentCommand.parse("COMMAND time set noon"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("command /"));
    }

    @Test
    void teleportIsAbsoluteAndLocaleFree() {
        AgentCommand.Teleport tp = (AgentCommand.Teleport) AgentCommand.parse("tp 100.5 140 -2000");
        assertEquals("tp @s 100.500 140.000 -2000.000", tp.asCommand());
        tp = (AgentCommand.Teleport) AgentCommand.parse("tp 0 120 0 90 25.5");
        assertEquals("tp @s 0.000 120.000 0.000 90.00 25.50", tp.asCommand());
        // The AZERTY trap of the keystroke runs: ^ ^ ^3 became an absolute teleport underground.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentCommand.parse("tp ^ ^ ^3"));
        assertTrue(e.getMessage().contains("relative"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("tp ~ 100 ~"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("tp 1 2 3 4"));
    }

    @Test
    void screenshotNamesStayInTheShotsFolder() {
        assertEquals(new AgentCommand.Screenshot("coast-01", false), AgentCommand.parse("screenshot coast-01"));
        assertEquals(new AgentCommand.Screenshot("a.b_c", true), AgentCommand.parse("screenshot a.b_c HUD"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("screenshot ../x"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("screenshot .hidden"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("screenshot a b"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("screenshot " + "x".repeat(65)));
    }

    @Test
    void waits() {
        assertEquals(new AgentCommand.WaitStable(120, 2), AgentCommand.parse("wait stable"));
        assertEquals(new AgentCommand.WaitStable(30, 5), AgentCommand.parse("wait stable 30 5"));
        assertEquals(new AgentCommand.WaitTicks(40), AgentCommand.parse("wait ticks 40"));
        assertEquals(new AgentCommand.WaitSeconds(1.5), AgentCommand.parse("wait seconds 1.5"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("wait ticks 2.5"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("wait seconds 0"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("wait"));
    }

    @Test
    void windowModes() {
        assertEquals(new AgentCommand.Window(AgentCommand.WindowMode.BACKGROUND),
                AgentCommand.parse("window background"));
        assertEquals(new AgentCommand.Window(AgentCommand.WindowMode.RESTORE), AgentCommand.parse("window Restore"));
        assertFalse(AgentCommand.parse("window minimize").needsPlayer());
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("window hide"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("window"));
    }

    @Test
    void flightsAndFrames() {
        assertEquals(new AgentCommand.Record("coast"), AgentCommand.parse("record start coast"));
        assertEquals(new AgentCommand.Record(null), AgentCommand.parse("record stop"));
        assertEquals(new AgentCommand.Replay("coast", 1, 0), AgentCommand.parse("replay coast"));
        assertEquals(new AgentCommand.Replay("coast", 0.5, 20), AgentCommand.parse("replay coast speed 0.5 every 20"));
        assertEquals(new AgentCommand.Frames(true), AgentCommand.parse("frames on"));
        assertTrue(AgentCommand.parse("replay coast").needsPlayer());
        assertFalse(AgentCommand.parse("record stop").needsPlayer());
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("replay ../x"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("replay coast every 1.5"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("replay coast speed"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("record start"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.parse("frames maybe"));
    }

    @Test
    void onlyWorldCommandsWaitForAPlayer() {
        assertTrue(AgentCommand.parse("tp 0 100 0").needsPlayer());
        assertTrue(AgentCommand.parse("wait stable").needsPlayer());
        assertFalse(AgentCommand.parse("screenshot title").needsPlayer());
        assertFalse(AgentCommand.parse("state").needsPlayer());
        assertFalse(AgentCommand.parse("quit").needsPlayer());
    }

    @Test
    void unknownVerbNamesTheKnownOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentCommand.parse("fly 10"));
        assertTrue(e.getMessage().contains("screenshot"));
    }
}
