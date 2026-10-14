package io.lakio.farfield.lod;

/**
 * Switches LOD drawing on and off ({@code /farfield render on|off}), to
 * compare frame rates in the same session. Side-neutral on purpose: the
 * command lives on the (integrated) server, the renderer on the client of
 * the same process; a dedicated server only stores the flag.
 */
public final class RenderToggle {

    private static volatile boolean enabled = true;

    private RenderToggle() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void set(boolean value) {
        enabled = value;
    }
}
