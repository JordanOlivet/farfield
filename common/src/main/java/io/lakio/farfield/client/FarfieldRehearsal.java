package io.lakio.farfield.client;

import io.lakio.farfield.client.render.FrameProbe;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.lod.RenderSettings;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Farfield's part in Rehearsal, the test harness mod (ADR-122): a run names
 * this class in {@code -Drehearsal.extensions}, and Rehearsal calls these
 * methods by name, so Farfield has no dependency on it and this class does
 * nothing without it. Adds the renderer's counters to {@code state}, keeps
 * {@code wait stable} waiting while LODs are wanted in view, meshed or
 * fetched, and adds the verbs {@code frames on|off} (the frame probe) and
 * {@code settings <tab>|close} (the settings screen). Client thread only.
 */
public final class FarfieldRehearsal {

    public String name() {
        return "lod";
    }

    public Map<String, Object> state() {
        LodRenderer.Snapshot r = LodRenderer.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", RenderSettings.enabled());
        m.put("disabled", r.disabled());
        m.put("drawing", r.drawing());
        m.put("drawn", r.drawn());
        m.put("resident", r.resident());
        m.put("wantedInView", r.wantedInView());
        m.put("meshing", r.meshing());
        m.put("streamPending", StreamClient.pending());
        m.put("holesOpen", r.holesOpen());
        m.put("frameProbe", FrameProbe.enabled());
        m.put("frameEvents", FrameProbe.events());
        return m;
    }

    /**
     * Busy while LODs are wanted in view, meshing or fetched; with LODs off
     * (the setting, or no OpenGL 4.6), nothing to wait for.
     */
    public Boolean quiet() {
        LodRenderer.Snapshot r = LodRenderer.snapshot();
        if (r.disabled() || !RenderSettings.enabled()) {
            return true;
        }
        return r.drawing() && r.wantedInView() == 0 && r.meshing() == 0 && StreamClient.pending() == 0;
    }

    public String run(List<String> words) {
        String verb = words.get(0).toLowerCase(Locale.ROOT);
        switch (verb) {
            case "frames" -> {
                if (words.size() != 2 || !words.get(1).equalsIgnoreCase("on") && !words.get(1).equalsIgnoreCase("off")) {
                    throw new IllegalArgumentException("frames takes on or off");
                }
                return FrameProbe.setEnabled(words.get(1).equalsIgnoreCase("on"));
            }
            case "settings" -> {
                String usage = "settings takes a tab number (0 first) or close";
                if (words.size() != 2) {
                    throw new IllegalArgumentException(usage);
                }
                Minecraft mc = Minecraft.getInstance();
                if (words.get(1).equalsIgnoreCase("close")) {
                    if (mc.screen instanceof FarfieldSettingsScreen screen) {
                        screen.onClose();
                    }
                    return "settings closed";
                }
                int tab;
                try {
                    tab = Integer.parseInt(words.get(1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(usage);
                }
                if (tab < 0 || tab > 15) {
                    throw new IllegalArgumentException(usage);
                }
                mc.setScreen(FarfieldSettingsScreen.onTab(null, tab));
                return "settings open on tab " + tab;
            }
            default -> {
                return null;
            }
        }
    }
}
