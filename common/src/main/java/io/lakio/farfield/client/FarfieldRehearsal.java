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
 * fetched, and adds the verbs {@code frames on|off} (the frame probe),
 * {@code settings <tab>|close} (the settings screen) and {@code video [<mod
 * id> [<page>]]|close} (the game's video settings: Sodium's screen when
 * Sodium 0.8 is installed, on a mod's page). Client thread only.
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
            case "video" -> {
                return video(words);
            }
            default -> {
                return null;
            }
        }
    }

    private static final String SODIUM_SCREEN = "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";
    private static final String SODIUM_CONFIG = "net.caffeinemc.mods.sodium.client.config.ConfigManager";

    /**
     * The game's video settings: Sodium's screen when Sodium (0.8 on) is
     * there, through its own methods by name (no compile dependency, the
     * same on every loader), else vanilla's. With a mod id, Sodium's screen
     * jumps to that mod's page ({@code <page>}, 0 first).
     */
    private static String video(List<String> words) {
        String usage = "video takes nothing, a mod id and an optional page number, or close";
        Minecraft mc = Minecraft.getInstance();
        if (words.size() == 2 && words.get(1).equalsIgnoreCase("close")) {
            mc.setScreen(null);
            return "video settings closed";
        }
        if (words.size() > 3) {
            throw new IllegalArgumentException(usage);
        }
        Class<?> sodium;
        try {
            sodium = Class.forName(SODIUM_SCREEN);
        } catch (ClassNotFoundException e) {
            if (words.size() > 1) {
                throw new IllegalArgumentException("no Sodium 0.8: its pages are not there");
            }
            mc.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options));
            return "video settings open (vanilla)";
        }
        try {
            Object screen = null;
            for (java.lang.reflect.Method m : sodium.getMethods()) {
                if (m.getName().equals("createScreen") && m.getParameterCount() == 1) {
                    screen = m.invoke(null, (Object) null);
                }
            }
            if (!(screen instanceof net.minecraft.client.gui.screens.Screen s)) {
                throw new IllegalStateException("Sodium's VideoSettingsScreen.createScreen not found");
            }
            mc.setScreen(s);
            if (words.size() == 1) {
                return "video settings open (Sodium)";
            }
            String mod = words.get(1);
            int page = 0;
            if (words.size() == 3) {
                try {
                    page = Integer.parseInt(words.get(2));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(usage);
                }
            }
            Object config = Class.forName(SODIUM_CONFIG).getField("CONFIG").get(null);
            List<?> mods = (List<?>) config.getClass().getMethod("getModOptions").invoke(config);
            for (Object options : mods) {
                if (mod.equals(options.getClass().getMethod("configId").invoke(options))) {
                    List<?> pages = (List<?>) options.getClass().getMethod("pages").invoke(options);
                    if (page < 0 || page >= pages.size()) {
                        throw new IllegalArgumentException(mod + " has " + pages.size() + " pages in Sodium");
                    }
                    for (java.lang.reflect.Method m : s.getClass().getMethods()) {
                        if (m.getName().equals("jumpToPage") && m.getParameterCount() == 1) {
                            m.invoke(s, pages.get(page));
                            return "video settings open (Sodium) on " + mod + " page " + page + " of " + pages.size();
                        }
                    }
                    throw new IllegalStateException("Sodium's jumpToPage not found");
                }
            }
            throw new IllegalArgumentException("no Sodium pages for the mod " + mod);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Sodium's video settings: " + e, e);
        }
    }
}
