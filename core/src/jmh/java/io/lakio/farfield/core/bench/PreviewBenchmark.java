package io.lakio.farfield.core.bench;

import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.BinaryGreedyMesher;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.MeshBuffer;
import io.lakio.farfield.core.preview.PreviewTile;
import io.lakio.farfield.core.preview.PreviewVoxels;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Single-thread cost of a noise preview mesh job (ADR-039), on a synthetic
 * tile: rolling land and sea around sea level, a forest of 10 spruces per
 * chunk, bare or snowy (ADR-049), or no trees. Split into the parts a job runs: the whole section, the six
 * neighbor layers (meshing preview water), and the mesher. No corpus needed.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"-Xmx2g"})
public class PreviewBenchmark {

    private static final int AIR = 0;
    private static final int GRASS = 1;
    private static final int DIRT = 2;
    private static final int STONE = 3;
    private static final int WATER = 4;
    private static final int LOG = 5;
    private static final int LEAVES = 6;
    private static final int SNOW = 7;
    private static final int ICE = 8;
    private static final int SEA = 63;
    private static final MaterialClassifier CLASSES = new MaterialClassifier() {
        @Override
        public int classOf(int m) {
            return m == AIR ? MaterialClassifier.AIR
                    : m == WATER || m == ICE ? MaterialClassifier.TRANSLUCENT : MaterialClassifier.OPAQUE;
        }

        @Override
        public boolean isFluid(int m) {
            return m == WATER;
        }
    };

    @Param({"0", "2", "4"})
    public int level;

    /** {@code frozen}: no trees, the sea under ice (two translucent layers, ADR-055). */
    @Param({"none", "bare", "snowy", "frozen"})
    public String forest;

    private PreviewVoxels.Tiles tiles;
    private int sy;
    private LodSection section;
    private final BinaryGreedyMesher mesher = new BinaryGreedyMesher();
    private final MeshBuffer out = new MeshBuffer();

    @Setup
    public void setup() {
        PreviewTile t = new PreviewTile();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                double x = i * PreviewTile.GRID;
                double z = k * PreviewTile.GRID;
                t.setHeight(i, k, (int) (SEA + 12 * Math.sin(x / 47.0) * Math.cos(z / 61.0) + 4 * Math.sin(z / 13.0)));
            }
        }
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                t.setCell(cx, cz, GRASS, DIRT);
                t.setFrozen(cx, cz, forest.equals("frozen"));
            }
        }
        t.setIce(ICE);
        t.setBelow(STONE);
        t.setWater(WATER, SEA);
        int spruce = t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 8, 2, 6, PreviewTile.TIERED,
                forest.equals("snowy") ? SNOW : -1));
        java.util.Random random = new java.util.Random(1);
        for (int chunkZ = 0; chunkZ < PreviewTile.CHUNKS; chunkZ++) {
            for (int chunkX = 0; chunkX < PreviewTile.CHUNKS; chunkX++) {
                for (int n = 0; n < 10; n++) {
                    int bx = chunkX * 16 + random.nextInt(16);
                    int bz = chunkZ * 16 + random.nextInt(16);
                    if ((forest.equals("bare") || forest.equals("snowy")) && t.height(bx, bz) > SEA) {
                        t.addTree(bx, bz, spruce);
                    }
                }
            }
        }
        tiles = (x, z) -> t; // every tile the same: the section never meets a missing one
        // The section row holding the sea level at this level.
        sy = Math.floorDiv(SEA, LodSection.SIZE << level);
        section = build(-1, 0);
    }

    private LodSection build(int axis, int index) {
        return PreviewVoxels.build(level, 1, sy, 1, tiles, null, AIR, -64, -1, -1, axis, index);
    }

    @Benchmark
    public LodSection wholeSection() {
        return build(-1, 0);
    }

    @Benchmark
    public int sixLayers() {
        int n = 0;
        for (int d = 0; d < 6; d++) {
            LodSection layer = build(d >> 1, (d & 1) != 0 ? 0 : LodSection.SIZE - 1);
            n += layer == null ? 0 : 1;
        }
        return n;
    }

    @Benchmark
    public int mesh() {
        mesher.mesh(section, CLASSES, null, out);
        return out.total();
    }
}
