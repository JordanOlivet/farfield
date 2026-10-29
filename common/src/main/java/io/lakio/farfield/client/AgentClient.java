package io.lakio.farfield.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.core.agent.AgentCommand;
import io.lakio.farfield.core.agent.AgentInbox;
import io.lakio.farfield.core.agent.SettleWatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;

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
            default -> throw new IllegalStateException("not a waiting command: " + e.text());
        }
        return true;
    }

    /** The last frame of the main render target, as the game's own screenshot reads it. */
    private void screenshot(Minecraft mc, AgentCommand.Screenshot s) {
        Path file = inbox.shots().resolve(s.name() + ".png");
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(file);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("path", file.toAbsolutePath().toString());
            fields.put("width", image.getWidth());
            fields.put("height", image.getHeight());
            reply(current, true, fields);
        } catch (IOException | RuntimeException ex) {
            Farfield.LOG.warn("Agent inbox: screenshot {} failed", file, ex);
            reply(current, false, Map.of("error", String.valueOf(ex.getMessage())));
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
