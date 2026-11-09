package io.lakio.farfield.core.shader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Which LOD contract of a shader pack Farfield follows (ADR-113, ADR-119),
 * from the pack's files: Voxy's when it ships {@code voxy.json}, else
 * Distant Horizons' when it ships {@code dh_terrain.fsh} or {@code .vsh};
 * a forced contract wins when the pack ships it.
 */
public final class ContractChoice {

    public static final int AUTO = 0;
    public static final int VOXY = 1;
    public static final int DH = 2;

    /** How deep the pack's files are searched ({@code shaders/world0/dh_terrain.fsh} is 2 deep). */
    private static final int DEPTH = 4;

    /** What a pack ships. */
    public record Shipped(boolean voxy, boolean dh) {
    }

    private ContractChoice() {
    }

    /**
     * Lists the contracts a pack ships.
     *
     * @param root the pack's {@code shaders} folder, on disk or inside its zip
     */
    public static Shipped scan(Path root) throws IOException {
        boolean voxy = false;
        boolean dh = false;
        try (Stream<Path> files = Files.walk(root, DEPTH)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path name = p.getFileName();
                if (name == null) {
                    continue;
                }
                String n = name.toString();
                voxy |= n.equals("voxy.json");
                dh |= n.equals("dh_terrain.fsh") || n.equals("dh_terrain.vsh");
            }
        }
        return new Shipped(voxy, dh);
    }

    /**
     * The contract followed: {@link #DH} when forced and shipped, or when the
     * pack ships DH's only; else {@link #VOXY} (also for a pack with neither,
     * whose LODs then stay off under it).
     */
    public static int pick(Shipped shipped, int forced) {
        if (forced == DH && shipped.dh() || forced == AUTO && !shipped.voxy() && shipped.dh()) {
            return DH;
        }
        return VOXY;
    }
}
