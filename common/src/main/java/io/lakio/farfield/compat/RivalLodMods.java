package io.lakio.farfield.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * Other mods that draw far terrain as LODs (ADR-167). Beside one of them,
 * Farfield's renderer stays off and its shader pack hooks are not applied:
 * two LOD engines over the same Iris pipeline and the same GPU memory took
 * the PC down once (a video memory bug check at a world's first frame,
 * Distant Horizons and Farfield under a shader pack's DH contract). Found by
 * a class of each as a resource, so nothing of theirs loads and the check
 * works in a mixin config plugin, before the game starts.
 */
public final class RivalLodMods {

    /** A mod and a class it always ships (its public API or its client entry point). */
    private record Rival(String name, String resource) {
    }

    private static final List<Rival> RIVALS = List.of(
            new Rival("Distant Horizons", "com/seibel/distanthorizons/api/DhApi.class"),
            new Rival("Voxy", "me/cortex/voxy/client/VoxyClient.class"));

    private static List<String> present;

    private RivalLodMods() {
    }

    /** The names of the rival LOD mods installed, empty when none. */
    public static synchronized List<String> present() {
        if (present == null) {
            ClassLoader loader = RivalLodMods.class.getClassLoader();
            List<String> found = new ArrayList<>();
            for (Rival r : RIVALS) {
                if (loader.getResource(r.resource()) != null) {
                    found.add(r.name());
                }
            }
            present = List.copyOf(found);
        }
        return present;
    }

    /** True when one is installed. */
    public static boolean any() {
        return !present().isEmpty();
    }

    /** Their names for a message: "Distant Horizons", or "Distant Horizons and Voxy". */
    public static String names() {
        List<String> p = present();
        if (p.size() <= 1) {
            return String.join("", p);
        }
        return String.join(", ", p.subList(0, p.size() - 1)) + " and " + p.get(p.size() - 1);
    }
}
