package io.lakio.farfield.client.render.iris;

import io.lakio.farfield.Farfield;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Which LOD contract of a shader pack Farfield follows (ADR-113, ADR-119):
 * Voxy's when the pack ships it, else Distant Horizons' when it ships
 * {@code dh_terrain}. Chosen when Iris loads the pack, before its sources
 * are read, because the pack's code switches on the contract's macro
 * ({@code VOXY} or {@code DISTANT_HORIZONS}); a debug switch can force one.
 */
public final class PackContract {

    public static final int AUTO = 0;
    public static final int VOXY = 1;
    public static final int DH = 2;

    private static volatile int forced = AUTO;
    private static volatile int chosen = VOXY;
    private static volatile boolean applied;

    private PackContract() {
    }

    /** Whether a pack was loaded with a contract's macro (the Iris mixin ran). */
    public static boolean applied() {
        return applied;
    }

    /** The contract of the pack Iris loaded last. */
    public static int chosen() {
        return chosen;
    }

    public static int forced() {
        return forced;
    }

    /** Forces a contract from the next pack load on ({@link #AUTO}: the pack's own choice). */
    public static void force(int contract) {
        forced = contract;
    }

    public static String name(int contract) {
        return switch (contract) {
            case VOXY -> "Voxy";
            case DH -> "Distant Horizons";
            default -> "auto";
        };
    }

    /**
     * Chooses the contract of a pack being loaded and returns its macro.
     *
     * @param root the pack's {@code shaders} folder (inside its zip or not)
     */
    public static String choose(Path root) {
        boolean voxy = false;
        boolean dh = false;
        try (Stream<Path> files = Files.walk(root, 4)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path name = p.getFileName();
                if (name == null) {
                    continue;
                }
                String n = name.toString();
                voxy |= n.equals("voxy.json");
                dh |= n.equals("dh_terrain.fsh") || n.equals("dh_terrain.vsh");
            }
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("Shader pack LODs: cannot list the pack's files ({}), Voxy's contract assumed", e.toString());
            voxy = true;
        }
        int pick;
        if (forced == DH && dh || forced == AUTO && !voxy && dh) {
            pick = DH;
        } else {
            pick = VOXY;
        }
        chosen = pick;
        applied = true;
        Farfield.LOG.info("Shader pack LODs: the pack ships {}{}, {} contract followed{}",
                voxy ? "Voxy's contract" : "no Voxy contract", dh ? " and DH's" : ", no DH programs",
                name(pick), forced != AUTO ? " (forced " + name(forced) + ")" : "");
        return pick == DH ? "DISTANT_HORIZONS" : "VOXY";
    }
}
