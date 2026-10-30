package io.lakio.farfield.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.render.FrameProbe;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.core.agent.AgentCommand;
import io.lakio.farfield.core.agent.AgentInbox;
import io.lakio.farfield.core.agent.SettleWatch;
import io.lakio.farfield.core.debug.FlightPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets a test agent drive the dev client through files, with no keystroke
 * and no window focus (ADR-095): the commands of {@link AgentInbox} in
 * {@code <game dir>/farfield-agent/}, run one after the other at the end of
 * client ticks. Off unless the JVM runs with {@code -Dfarfield.agent=true},
 * which the dev client runs set; a pack instance gets it as a JVM argument.
 * Client thread only.
 */
public final class AgentClient {

    public static final boolean ENABLED = Boolean.getBoolean("farfield.agent");
    private static final String FOLDER = "farfield-agent";
    private static final int POLL_TICKS = 4;
    /** Frames rendered with the HUD hidden before a screenshot reads one. */
    private static final int HUD_HIDE_TICKS = 2;

    private static AgentClient instance;
    private static boolean failed;

    private final AgentInbox inbox;
    private final ArrayDeque<AgentInbox.Entry> queue = new ArrayDeque<>();
    private long ticks;
    /** The command spanning several ticks, null when none. */
    private AgentInbox.Entry current;
    private SettleWatch settle;
    private long waitUntilTick;
    private long waitUntilNanos;
    private boolean hudHidden;
    private boolean hudWas;
    /** The flight being replayed, where in it (in recorded ticks), the ticks played and when it began. */
    private FlightPath replay;
    private double replayAt;
    private int replayPlayed;
    private long replayStart;
    private long replayEvents;
    /** A replay starting far from the player teleports there first, then waits this long. */
    private static final int REPLAY_TELEPORT_TICKS = 40;
    private static final double REPLAY_TELEPORT_DISTANCE = 16;

    private AgentClient(AgentInbox inbox) {
        this.inbox = inbox;
    }

    /** End of every client tick. */
    public static void tick(Minecraft mc) {
        if (!ENABLED || failed) {
            return;
        }
        AgentClient a = instance;
        if (a == null) {
            Path dir = mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve(FOLDER);
            try {
                a = new AgentClient(AgentInbox.open(dir));
            } catch (IOException e) {
                failed = true;
                Farfield.LOG.error("Agent inbox: cannot open {}", dir, e);
                return;
            }
            instance = a;
            // A covered or unfocused window must not pause a single player world under the agent.
            mc.options.pauseOnLostFocus = false;
            Farfield.LOG.info("Agent inbox on: {} ({} command files of an earlier run removed)",
                    dir.toAbsolutePath(), a.inbox.staleFiles());
        }
        a.run(mc);
    }

    private void run(Minecraft mc) {
        ticks++;
        if (ticks % POLL_TICKS == 0) {
            try {
                queue.addAll(inbox.poll());
            } catch (IOException e) {
                Farfield.LOG.warn("Agent inbox: reading the command files failed", e);
            }
        }
        // Several instant commands may run in one tick; a waiting one holds the rest.
        while (true) {
            if (current != null) {
                if (!step(mc)) {
                    return;
                }
                current = null;
            }
            AgentInbox.Entry next = queue.peek();
            if (next == null) {
                return;
            }
            if (next.command() != null && next.command().needsPlayer() && mc.player == null) {
                return; // a world is loading: the command waits for it
            }
            queue.poll();
            current = next;
            if (!start(mc)) {
                return;
            }
            current = null;
        }
    }

    /** Starts {@link #current}; true when it is already done. */
    private boolean start(Minecraft mc) {
        AgentInbox.Entry e = current;
        if (e.command() == null) {
            reply(e, false, Map.of("error", e.error()));
            return true;
        }
        LocalPlayer player = mc.player;
        switch (e.command()) {
            case AgentCommand.Command c -> {
                player.connection.sendCommand(c.text());
                reply(e, true, Map.of());
            }
            case AgentCommand.Teleport t -> {
                player.connection.sendCommand(t.asCommand());
                reply(e, true, Map.of("sent", "/" + t.asCommand()));
            }
            case AgentCommand.Look l -> {
                player.setYRot(l.yaw());
                player.setXRot(l.pitch());
                player.yRotO = l.yaw();
                player.xRotO = l.pitch();
                player.setYHeadRot(l.yaw());
                reply(e, true, Map.of());
            }
            case AgentCommand.Screenshot s -> {
                if (s.hud() || mc.options.hideGui) {
                    screenshot(mc, s);
                    return true;
                }
                hudWas = mc.options.hideGui;
                mc.options.hideGui = true;
                hudHidden = true;
                waitUntilTick = ticks + HUD_HIDE_TICKS;
                return false;
            }
            case AgentCommand.State ignored -> reply(e, true, state(mc));
            case AgentCommand.WaitStable w -> {
                settle = new SettleWatch(System.nanoTime(), w.holdSeconds(), w.timeoutSeconds());
                return step(mc);
            }
            case AgentCommand.WaitTicks w -> {
                waitUntilTick = ticks + w.ticks();
                return false;
            }
            case AgentCommand.WaitSeconds w -> {
                waitUntilNanos = System.nanoTime() + (long) (w.seconds() * 1e9);
                return false;
            }
            case AgentCommand.Window w -> {
                AgentWindow.apply(mc.getWindow().getWindow(), switch (w.mode()) {
                    case MINIMIZE -> AgentWindow.Mode.MINIMIZED;
                    case BACKGROUND -> AgentWindow.Mode.BACKGROUND;
                    case RESTORE -> AgentWindow.Mode.NORMAL;
                });
                reply(e, true, Map.of());
            }
            case AgentCommand.Record r -> {
                String line = r.name() == null ? FlightRecorder.stop(mc) : FlightRecorder.start(mc, r.name());
                boolean ok = r.name() == null ? line.startsWith("Flight ") : line.startsWith("Recording");
                reply(e, ok, Map.of("message", line));
            }
            case AgentCommand.Frames f -> reply(e, true, Map.of("message", FrameProbe.setEnabled(f.on())));
            case AgentCommand.Replay r -> {
                return startReplay(mc, r);
            }
            case AgentCommand.Quit ignored -> {
                reply(e, true, Map.of());
                Farfield.LOG.info("Agent inbox: closing the game");
                mc.stop();
            }
        }
        return true;
    }

    /** Advances {@link #current}; true once it is done. */
    private boolean step(Minecraft mc) {
        AgentInbox.Entry e = current;
        switch (e.command()) {
            case AgentCommand.Screenshot s -> {
                if (ticks < waitUntilTick) {
                    return false;
                }
                screenshot(mc, s);
                if (hudHidden) {
                    mc.options.hideGui = hudWas;
                    hudHidden = false;
                }
            }
            case AgentCommand.WaitStable ignored -> {
                if (mc.player == null) {
                    return false;
                }
                LodRenderer.Snapshot r = LodRenderer.snapshot();
                long now = System.nanoTime();
                if (r.disabled()) {
                    reply(e, false, Map.of("outcome", "LOD rendering disabled", "lod", lod(r)));
                    settle = null;
                    return true; // nothing will ever settle: say so now, not at the timeout
                }
                boolean quiet = r.drawing() && r.wantedInView() == 0 && r.meshing() == 0 && StreamClient.pending() == 0;
                SettleWatch.Outcome outcome = settle.check(quiet, now);
                if (outcome == SettleWatch.Outcome.WAITING) {
                    return false;
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("outcome", outcome == SettleWatch.Outcome.SETTLED ? "settled" : "timed out");
                fields.put("seconds", Math.round(settle.elapsedSeconds(now) * 10) / 10.0);
                fields.put("lod", lod(r));
                reply(e, outcome == SettleWatch.Outcome.SETTLED, fields);
                settle = null;
            }
            case AgentCommand.WaitTicks ignored -> {
                if (ticks < waitUntilTick) {
                    return false;
                }
                reply(e, true, Map.of());
            }
            case AgentCommand.WaitSeconds ignored -> {
                if (System.nanoTime() < waitUntilNanos) {
                    return false;
                }
                reply(e, true, Map.of());
            }
            case AgentCommand.Replay r -> {
                return stepReplay(mc, r);
            }
            default -> throw new IllegalStateException("not a waiting command: " + e.text());
        }
        return true;
    }

    /** The last frame of the main render target, as the game's own screenshot reads it. */
    private void screenshot(Minecraft mc, AgentCommand.Screenshot s) {
        try {
            Map<String, Object> fields = writeShot(mc, s.name());
            reply(current, true, fields);
        } catch (IOException | RuntimeException ex) {
            Farfield.LOG.warn("Agent inbox: screenshot {} failed", s.name(), ex);
            reply(current, false, Map.of("error", String.valueOf(ex.getMessage())));
        }
    }

    private Map<String, Object> writeShot(Minecraft mc, String name) throws IOException {
        Path file = inbox.shots().resolve(name + ".png");
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(file);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("path", file.toAbsolutePath().toString());
            fields.put("width", image.getWidth());
            fields.put("height", image.getHeight());
            return fields;
        }
    }

    /** Loads the flight and goes to its start; true when it is already done (it failed). */
    private boolean startReplay(Minecraft mc, AgentCommand.Replay r) {
        Path file = FlightRecorder.folder(mc).resolve(r.name() + FlightPath.SUFFIX);
        FlightPath path;
        try {
            path = FlightPath.read(file);
        } catch (IOException ex) {
            reply(current, false, Map.of("error", String.valueOf(ex.getMessage())));
            return true;
        }
        LocalPlayer p = mc.player;
        String dimension = p.level().dimension().location().toString();
        if (!dimension.equals(path.dimension())) {
            reply(current, false, Map.of("error", "the flight is in " + path.dimension() + ", the player in " + dimension));
            return true;
        }
        replay = path;
        replayAt = 0;
        replayPlayed = 0;
        replayEvents = FrameProbe.events();
        FlightPath.Sample first = path.sample(0);
        waitUntilTick = ticks;
        if (p.position().distanceTo(new Vec3(first.x(), first.y(), first.z())) > REPLAY_TELEPORT_DISTANCE) {
            // A long first step would be refused by a remote server as moving too fast.
            p.connection.sendCommand(new AgentCommand.Teleport(first.x(), first.y(), first.z(), first.yaw(),
                    first.pitch()).asCommand());
            waitUntilTick = ticks + REPLAY_TELEPORT_TICKS;
        }
        if (r.every() > 0 && !mc.options.hideGui) {
            hudWas = false;
            mc.options.hideGui = true;
            hudHidden = true;
        }
        Farfield.LOG.info("Agent inbox: replaying {} ({} ticks at speed {})", file, path.ticks(), r.speed());
        return false;
    }

    /** One tick of the replay: the camera where the flight had it; true once the flight is over. */
    private boolean stepReplay(Minecraft mc, AgentCommand.Replay r) {
        if (ticks < waitUntilTick) {
            return false;
        }
        LocalPlayer p = mc.player;
        if (p == null) {
            endReplay(mc);
            reply(current, false, Map.of("error", "the player left the world", "ticks", replayPlayed));
            return true;
        }
        if (replayPlayed == 0) {
            replayStart = System.nanoTime();
        }
        FlightPath.Sample s = replay.at(replayAt);
        // Set at the end of the tick: the next tick keeps it as the old position, so frames interpolate.
        p.setDeltaMovement(Vec3.ZERO);
        p.setPos(s.x(), s.y(), s.z());
        p.setYRot(s.yaw());
        p.setXRot(s.pitch());
        p.setYHeadRot(s.yaw());
        if (r.every() > 0 && replayPlayed % r.every() == 0) {
            try {
                writeShot(mc, String.format(java.util.Locale.ROOT, "%s-%05d", r.name(), replayPlayed));
            } catch (IOException | RuntimeException ex) {
                Farfield.LOG.warn("Agent inbox: replay screenshot failed", ex);
            }
        }
        replayPlayed++;
        replayAt += r.speed();
        if (replayAt <= replay.ticks() - 1 + 1e-9) {
            return false;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ticks", replayPlayed);
        fields.put("seconds", Math.round((System.nanoTime() - replayStart) / 1e8) / 10.0);
        fields.put("frameEvents", FrameProbe.events() - replayEvents);
        fields.put("frameProbe", FrameProbe.enabled());
        endReplay(mc);
        reply(current, true, fields);
        return true;
    }

    private void endReplay(Minecraft mc) {
        replay = null;
        if (hudHidden) {
            mc.options.hideGui = hudWas;
            hudHidden = false;
        }
    }

    private Map<String, Object> state(Minecraft mc) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tick", ticks);
        fields.put("fps", mc.getFps());
        LocalPlayer p = mc.player;
        fields.put("inWorld", p != null);
        if (p != null) {
            fields.put("dimension", p.level().dimension().location().toString());
            fields.put("x", Math.round(p.getX() * 100) / 100.0);
            fields.put("y", Math.round(p.getY() * 100) / 100.0);
            fields.put("z", Math.round(p.getZ() * 100) / 100.0);
            fields.put("yaw", Math.round(p.getYRot() * 10) / 10.0);
            fields.put("pitch", Math.round(p.getXRot() * 10) / 10.0);
        }
        fields.put("screen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        fields.put("lod", lod(LodRenderer.snapshot()));
        return fields;
    }

    private static Map<String, Object> lod(LodRenderer.Snapshot r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disabled", r.disabled());
        m.put("drawing", r.drawing());
        m.put("drawn", r.drawn());
        m.put("resident", r.resident());
        m.put("wantedInView", r.wantedInView());
        m.put("meshing", r.meshing());
        m.put("streamPending", StreamClient.pending());
        m.put("holesOpen", r.holesOpen());
        return m;
    }

    private void reply(AgentInbox.Entry e, boolean ok, Map<String, ?> fields) {
        Farfield.LOG.info("Agent inbox: {} line {} '{}': {}", e.file(), e.line(), e.text(), ok ? "ok" : "failed");
        try {
            inbox.reply(e, ok, fields);
        } catch (IOException ex) {
            Farfield.LOG.warn("Agent inbox: writing the reply failed", ex);
        }
    }
}
