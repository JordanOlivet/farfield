package io.lakio.farfield.server;

import io.lakio.farfield.core.preview.PreviewTile;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.function.IntPredicate;

/**
 * What the world's own features grow in each biome, measured from the
 * level-0 tiles they grew on (ADR-126, step 3 of
 * {@code docs/analysis/preview-coarse-to-fine.md}): the share of the
 * ground their tops cover, how high and how thick, with which top material
 * and how often snow over it; the plants per chunk; the ground's top and
 * filler blocks the surface rules lay, under the sea, on flat land and on
 * steep land. Coarse tiles take their canopy, plants and ground from it, so
 * no list of mods or features is written: a mod's forest gets its canopy
 * from its own trees. Kept for the server's session; biomes are by the
 * world's biome ids. Thread-safe.
 */
final class PreviewProfiles {

    /** Chunks of a biome measured before its profile counts. */
    static final int ENOUGH = 16;
    /** Ground cells of a kind measured before the ground's blocks count. */
    private static final int ENOUGH_CELLS = 8;
    /** Samples tried for a biome before it goes without a profile this session. */
    private static final int MAX_SAMPLES = 2;
    /** The ground's kinds of cells. */
    static final int UNDER_SEA = 0;
    static final int FLAT = 1;
    static final int STEEP = 2;

    /** A biome's canopy: the covered share of its columns, the top above the ground, its thickness, material, snowy share. */
    record Canopy(double cover, int top, int thickness, int material, double snow) {
    }

    private static final class Biome {
        int chunks;
        long columns;
        long covered;
        long topSum;
        long thicknessSum;
        long snowy;
        final Int2IntOpenHashMap tops = new Int2IntOpenHashMap();
        /** Plant material: sixteenths of a plant, summed over the chunks. */
        final Int2LongOpenHashMap plants = new Int2LongOpenHashMap();
        @SuppressWarnings("unchecked")
        final Long2IntOpenHashMap[] ground = {new Long2IntOpenHashMap(), new Long2IntOpenHashMap(),
                new Long2IntOpenHashMap()};
        int samples;
    }

    private final Int2ObjectOpenHashMap<Biome> biomes = new Int2ObjectOpenHashMap<>();
    /** Whether a material is a snow cover laid on top (snow layers, snow blocks, powder snow). */
    private final IntPredicate snow;
    private final int snowBlock;
    private final int cliffRise;

    /**
     * @param snow      whether a material is snow laid over what is under it
     * @param snowBlock the snow block, which the tiles lay on cold land over the rules' top
     * @param cliffRise the rise across a 4-block cell from which it counts as steep
     */
    PreviewProfiles(IntPredicate snow, int snowBlock, int cliffRise) {
        this.snow = snow;
        this.snowBlock = snowBlock;
        this.cliffRise = cliffRise;
    }

    private Biome biome(int id) {
        return biomes.computeIfAbsent(id, k -> new Biome());
    }

    /** Whether a biome has enough chunks measured for its profile. */
    synchronized boolean known(int biome) {
        Biome b = biomes.get(biome);
        return b != null && b.chunks >= ENOUGH;
    }

    /** Whether a biome still needs a sample and may have one more; counts it. */
    synchronized boolean takeSample(int biome) {
        Biome b = biome(biome);
        if (b.chunks >= ENOUGH || b.samples >= MAX_SAMPLES) {
            return false;
        }
        b.samples++;
        return true;
    }

    /** Biomes with a profile. */
    synchronized int size() {
        int n = 0;
        for (Biome b : biomes.values()) {
            n += b.chunks >= ENOUGH ? 1 : 0;
        }
        return n;
    }

    /**
     * Measures the chunks [lx0, lx0 + n) x [lz0, lz0 + n) of a level-0 tile
     * whose features grew, each under its own biome.
     */
    synchronized void record(PreviewTile tile, int lx0, int lz0, int n) {
        int sea = tile.water() >= 0 ? tile.seaLevel() : Integer.MIN_VALUE;
        for (int lz = lz0; lz < lz0 + n; lz++) {
            for (int lx = lx0; lx < lx0 + n; lx++) {
                int id = tile.biome(lx, lz);
                if (id < 0) {
                    continue;
                }
                Biome b = biome(id);
                b.chunks++;
                recordColumns(b, tile, lx, lz);
                for (int entry : tile.plants(lx, lz)) {
                    b.plants.addTo(tile.plantMaterial(entry & 0xFF), entry >>> 8);
                }
                recordGround(b, tile, lx, lz, sea);
            }
        }
    }

    private void recordColumns(Biome b, PreviewTile tile, int lx, int lz) {
        int v = PreviewTile.FEATURE_VOXEL;
        int fx0 = (lx * 16 + PreviewTile.FEATURE_MARGIN) / v;
        int fz0 = (lz * 16 + PreviewTile.FEATURE_MARGIN) / v;
        for (int fz = fz0; fz < fz0 + 16 / v; fz++) {
            for (int fx = fx0; fx < fx0 + 16 / v; fx++) {
                b.columns++;
                int[] runs = tile.featureColumn(fx, fz);
                if (runs.length == 0) {
                    continue;
                }
                int r = runs.length - 2;
                boolean snowy = false;
                if (snow.test(runs[r + 1]) && r >= 2) {
                    snowy = true;
                    r -= 2;
                }
                int bx = fx * v - PreviewTile.FEATURE_MARGIN;
                int bz = fz * v - PreviewTile.FEATURE_MARGIN;
                int ground = tile.height(bx, bz);
                int top = PreviewTile.featureRunY(runs[r]) + PreviewTile.featureRunBlocks(runs[r]) - ground;
                if (top <= 0 || snow.test(runs[r + 1])) {
                    continue; // nothing above the ground there, or snow alone
                }
                int thickness = 0;
                for (int i = 0; i <= r; i += 2) {
                    thickness += PreviewTile.featureRunBlocks(runs[i]);
                }
                b.covered++;
                b.topSum += top;
                b.thicknessSum += Math.min(15, thickness);
                b.snowy += snowy ? 1 : 0;
                b.tops.addTo(runs[r + 1], 1);
            }
        }
    }

    private void recordGround(Biome b, PreviewTile tile, int lx, int lz, int sea) {
        for (int gz = 0; gz < 4; gz++) {
            for (int gx = 0; gx < 4; gx++) {
                int cx = lx * 4 + gx;
                int cz = lz * 4 + gz;
                int h = Math.max(Math.max(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                        Math.max(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                int low = Math.min(Math.min(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                        Math.min(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                int kind = h <= sea ? UNDER_SEA : h - low >= cliffRise ? STEEP : FLAT;
                int top = tile.top(cx, cz);
                int filler = tile.filler(cx, cz);
                if (top == snowBlock && filler != snowBlock) {
                    // The tiles lay snow over the rules' top on cold land: that top is the filler.
                    top = filler;
                }
                b.ground[kind].addTo(pair(top, filler), 1);
            }
        }
    }

    private static long pair(int top, int filler) {
        return (long) top << 32 | (filler & 0xFFFFFFFFL);
    }

    /** A biome's canopy, or null without a profile; a cover of 0 when nothing grows there. */
    synchronized Canopy canopy(int biome) {
        Biome b = biomes.get(biome);
        if (b == null || b.chunks < ENOUGH) {
            return null;
        }
        if (b.covered == 0) {
            return new Canopy(0, 0, 0, -1, 0);
        }
        int material = -1;
        int best = 0;
        for (var e : b.tops.int2IntEntrySet()) {
            if (e.getIntValue() > best) {
                best = e.getIntValue();
                material = e.getIntKey();
            }
        }
        return new Canopy(b.covered / (double) b.columns, (int) Math.round(b.topSum / (double) b.covered),
                (int) Math.round(b.thicknessSum / (double) b.covered), material, b.snowy / (double) b.covered);
    }

    /** A biome's plants: pairs of material and mean sixteenths of a plant per chunk; empty without a profile. */
    synchronized int[] plants(int biome) {
        Biome b = biomes.get(biome);
        if (b == null || b.chunks < ENOUGH) {
            return new int[0];
        }
        int[] out = new int[b.plants.size() * 2];
        int n = 0;
        for (var e : b.plants.int2LongEntrySet()) {
            long perChunk = Math.round(e.getLongValue() / (double) b.chunks);
            if (perChunk > 0) {
                out[n++] = e.getIntKey();
                out[n++] = (int) Math.min(0xFFFFFF, perChunk);
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * The most common top and filler of a biome's cells of a kind ({@link
     * #UNDER_SEA}, {@link #FLAT}, {@link #STEEP}), {@code top << 32 |
     * filler}, as the rules lay it (the snow the tiles lay over it on cold
     * land left out); -1 without enough cells measured.
     */
    synchronized long ground(int biome, int kind) {
        Biome b = biomes.get(biome);
        if (b == null) {
            return -1;
        }
        int total = 0;
        long best = -1;
        int bestCount = 0;
        for (var e : b.ground[kind].long2IntEntrySet()) {
            total += e.getIntValue();
            if (e.getIntValue() > bestCount) {
                bestCount = e.getIntValue();
                best = e.getLongKey();
            }
        }
        return total < ENOUGH_CELLS ? -1 : best;
    }
}
