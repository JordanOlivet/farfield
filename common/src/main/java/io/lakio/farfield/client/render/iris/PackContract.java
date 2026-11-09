package io.lakio.farfield.client.render.iris;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.shader.ContractChoice;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Which LOD contract of a shader pack Farfield follows (ADR-113, ADR-119):
 * Voxy's when the pack ships it, else Distant Horizons' when it ships
 * {@code dh_terrain}. Chosen when Iris loads the pack, before its sources
 * are read, because the pack's code switches on the contract's macro
 * ({@code VOXY} or {@code DISTANT_HORIZONS}); a debug switch can force one.
 */
public final class PackContract {

    public static final int AUTO = ContractChoice.AUTO;
    public static final int VOXY = ContractChoice.VOXY;
    public static final int DH = ContractChoice.DH;

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
        ContractChoice.Shipped shipped;
        try {
            shipped = ContractChoice.scan(root);
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("Shader pack LODs: cannot list the pack's files ({}), Voxy's contract assumed", e.toString());
            shipped = new ContractChoice.Shipped(true, false);
        }
        boolean voxy = shipped.voxy();
        boolean dh = shipped.dh();
        int pick = ContractChoice.pick(shipped, forced);
        chosen = pick;
        applied = true;
        Farfield.LOG.info("Shader pack LODs: the pack ships {}{}, {} contract followed{}",
                voxy ? "Voxy's contract" : "no Voxy contract", dh ? " and DH's" : ", no DH programs",
                name(pick), forced != AUTO ? " (forced " + name(forced) + ")" : "");
        return pick == DH ? "DISTANT_HORIZONS" : "VOXY";
    }
}
