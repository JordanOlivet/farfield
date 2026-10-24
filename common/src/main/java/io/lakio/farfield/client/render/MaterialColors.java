package io.lakio.farfield.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.ShapeBoxes;
import io.lakio.farfield.core.preview.PreviewVoxels;
import io.lakio.farfield.lod.BlockKeys;
import io.lakio.farfield.mixin.client.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks of an LOD world's block states for the shader (ADR-010): per face, a
 * baked 16x16 texture for the near levels ({@link FaceTextures}) and its
 * mean color for the far ones.
 *
 * <p>Per block-state id, {@link #STRIDE} ints; per face direction (the order
 * of {@code Quad}): an untinted color, a biome-tinted color (both ABGR8 in
 * sRGB) and the face's tiles; then the height of the block's top in blocks
 * (float bits; below 1 for partial blocks and fluids, which the shader draws
 * at that height on the finest level); then {@link #BOX_COUNT} boxes of a
 * shaped block ({@code ShapeBoxes}, 0 past its last box; all 0 for other
 * blocks), which the shader draws on the finest level. The face's color is {@code untinted + tinted *
 * tint} (summed in linear space), the tint coming from {@link BiomeTints}
 * for the quad's biome and the kind in the tinted color's alpha byte (its
 * top bit: {@link #CUTOUT}, set on faces without tint too); the
 * untinted color's alpha is the face's coverage (water, glass, ice are below
 * 1). Tiles: untinted tile + 1 in the low 16 bits, tinted tile + 1 in the
 * high 16 bits, 0 for none; the textured color is
 * {@code untintedTile + tintedTile * tint}, its alpha the untinted tile's.
 *
 * <p>A face is baked by projecting the model's quads on that side onto a
 * 16x16 grid (positions and UVs from the vertex data, so rotated and partial
 * faces land where the block shows them), layered in drawing order (the
 * grass overlay over its side) in linear space. Grid columns follow the
 * face's U axis and rows its V axis, as {@code lod.vert} maps them, one tile
 * per block. Opaque materials with holes (leaves) get their holes filled
 * with their own color darkened, as vanilla's fast leaves; the mean color
 * then matches the texture's far look. Partial blocks (snow layers, slabs)
 * get the rest of their faces filled with their own color: the finest
 * level draws only their shape's part, coarser levels a full cube that
 * must not show holes. Constant block colors (birch, spruce)
 * are baked in; fluids and models without quads use their particle sprite
 * over the whole face; a failure falls back to the map color without a
 * texture.
 *
 * <p>Built on the render thread under a time budget per frame; materials not
 * built yet read as tinted for the mesher threads ({@link #tinted}).
 */
final class MaterialColors {

    static final int STRIDE = 27;
    /**
     * Top bit of a face's tinted color word (its alpha byte holds the tint
     * kind, below 8): the face's texture has texels under half coverage, which
     * the opaque pass cuts out; the depth pre-pass reads the texture only then.
     */
    static final int CUTOUT = 1 << 31;
    /** Offset of the top height (float bits, in blocks) in a block state's entry. */
    static final int HEIGHT = 18;
    /** Offset of the boxes of a shaped block in a block state's entry. */
    static final int BOXES = 19;
    static final int BOX_COUNT = ShapeBoxes.MAX;

    private static final int T = FaceTextures.TILE;
    private static final int PIXELS = T * T;
    private static final long BUDGET_NANOS = 3_000_000L;
    private static final int FALLBACK = abgr(0x808080, 0xFF);
    /** Brightness of what shows through the holes of opaque materials (inner leaves). */
    private static final float HOLE_SHADE = 0.45f;
    /** Quad direction order: -X, +X, -Y, +Y, -Z, +Z. */
    private static final Direction[] DIRECTIONS = {
            Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

    private int[] colors = new int[0];
    private int count;
    /** Published to the mesher threads; replaced, never modified. */
    private volatile boolean[] tinted = new boolean[0];
    /** Per id: a face has {@link #CUTOUT}; read by the mesher threads as {@link #tinted}. */
    private volatile boolean[] cutout = new boolean[0];

    private final FaceTextures textures;
    private final Map<TextureAtlasSprite, Sprite> sprites = new IdentityHashMap<>();
    private final TintProbe probe = new TintProbe();
    private final RandomSource random = RandomSource.create();

    // Per-face bake: premultiplied untinted and tinted linear RGB and coverage per pixel.
    private final float[][] untinted = new float[6][PIXELS * 3];
    private final float[][] tintedPart = new float[6][PIXELS * 3];
    private final float[][] coverage = new float[6][PIXELS];
    private final int[] kinds = new int[6];
    private final boolean[] hasQuads = new boolean[6];
    private final float[] tileRgb = new float[PIXELS * 3];
    private final float[] tileAlpha = new float[PIXELS];
    private int tintKind;
    private final float[] tintConstant = new float[3];

    /** Frame 0 of a sprite in linear RGB (not premultiplied) and alpha. */
    private record Sprite(int width, int height, float[] rgb, float[] alpha) {
    }

    MaterialColors(FaceTextures textures) {
        this.textures = textures;
    }

    /**
     * Builds the looks of new ids within the frame's budget.
     *
     * @param probeBiome the biome block colors are probed in (plains)
     * @return the first id built, or -1 if none
     */
    int update(KeyRegistry registry, MaterialClassifier classes, BiomeTints tints, Biome probeBiome) {
        int size = registry.size();
        if (size == count) {
            return -1;
        }
        if (colors.length < size * STRIDE) {
            colors = Arrays.copyOf(colors, Math.max(size, colors.length / STRIDE * 2) * STRIDE);
        }
        boolean[] flags = Arrays.copyOf(tinted, size);
        boolean[] holes = Arrays.copyOf(cutout, size);
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int first = count;
        int id = count;
        while (id < size) {
            int meshClass = classes.classOf(id);
            String key = registry.key(id);
            flags[id] = build(key, id * STRIDE, meshClass, tints, probeBiome);
            for (int d = 0; d < 6; d++) {
                holes[id] |= (colors[id * STRIDE + d * 3 + 1] & CUTOUT) != 0;
            }
            colors[id * STRIDE + HEIGHT] = Float.floatToIntBits(height(key, meshClass));
            int boxes = Math.min(classes.boxCount(id), BOX_COUNT);
            for (int b = 0; b < BOX_COUNT; b++) {
                colors[id * STRIDE + BOXES + b] = b < boxes ? classes.box(id, b) : 0;
            }
            id++;
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        count = id;
        tinted = Arrays.copyOf(flags, id);
        cutout = Arrays.copyOf(holes, id);
        return first;
    }

    /**
     * Height of a block's top in blocks, drawn on the finest level: partial
     * blocks their shape's, fluids vanilla's surface (8/9, so the LOD sea
     * meets vanilla's without a slit at the edge), anything else 1.
     */
    private static float height(String key, int meshClass) {
        if (meshClass != MaterialClassifier.PARTIAL && meshClass != MaterialClassifier.TRANSLUCENT) {
            return 1f;
        }
        BlockState state = BlockKeys.state(key);
        if (state == null) {
            return 1f;
        }
        if (meshClass == MaterialClassifier.PARTIAL) {
            return BlockKeys.heightSixteenths(state) / 16f;
        }
        return BlockKeys.lookOf(state).getBlock() instanceof LiquidBlock ? 8f / 9f : 1f;
    }

    /** Forgets every look (resource reload, other world): they are built again. */
    void reset() {
        count = 0;
        sprites.clear();
        textures.reset();
        tinted = new boolean[0];
        cutout = new boolean[0];
    }

    /** Whether a material's quads must carry their biome; true for materials not built yet. Any thread. */
    boolean tinted(int material) {
        boolean[] t = tinted;
        return material >= t.length || t[material];
    }

    /** Whether a material's faces may have holes to cut out ({@link #CUTOUT}); true for materials not built yet. Any thread. */
    boolean cutout(int material) {
        boolean[] c = cutout;
        return material >= c.length || c[material];
    }

    /** Whether a material's look is built (its {@link #cutout} flag is final). Any thread. */
    boolean built(int material) {
        return material < cutout.length;
    }

    int[] colors() {
        return colors;
    }

    int count() {
        return count;
    }

    /** Builds one state at {@code off}; returns whether a face depends on the biome. */
    private boolean build(String key, int off, int meshClass, BiomeTints tints, Biome probeBiome) {
        BlockState state = BlockKeys.state(key);
        if (state == null) {
            if (!key.startsWith(PreviewVoxels.MASK_GROUND_KEY)) { // the preview's masks: never drawn
                Farfield.LOG.warn("LOD look of unknown block state {}: drawn gray", key);
            }
            fillFlat(off, FALLBACK);
            return false;
        }
        try {
            return build(BlockKeys.lookOf(state), off, meshClass, tints, probeBiome);
        } catch (RuntimeException | LinkageError e) {
            Farfield.LOG.warn("LOD look of {} failed, map color used instead", key, e);
            fillFlat(off, mapColor(state));
            return false;
        }
    }

    private void fillFlat(int off, int color) {
        for (int d = 0; d < 6; d++) {
            colors[off + d * 3] = color;
            colors[off + d * 3 + 1] = 0;
            colors[off + d * 3 + 2] = 0;
        }
    }

    private boolean build(BlockState state, int off, int meshClass, BiomeTints tints, Biome probeBiome) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        BlockColors blockColors = mc.getBlockColors();
        for (int d = 0; d < 6; d++) {
            Arrays.fill(untinted[d], 0);
            Arrays.fill(tintedPart[d], 0);
            Arrays.fill(coverage[d], 0);
            kinds[d] = -1;
            hasQuads[d] = false;
        }
        if (meshClass == MaterialClassifier.PLANT) {
            return buildPlant(state, model, blockColors, off, tints, probeBiome);
        }
        int lastTintIndex = Integer.MIN_VALUE;
        for (int d = 0; d < 6; d++) {
            random.setSeed(42);
            List<BakedQuad> quads = model.getQuads(state, DIRECTIONS[d], random);
            for (BakedQuad quad : quads) {
                if (quad.isTinted() && quad.getTintIndex() != lastTintIndex) {
                    lastTintIndex = quad.getTintIndex();
                    probeTint(blockColors, state, lastTintIndex, tints, probeBiome);
                }
                rasterize(d, quad, quad.isTinted());
            }
        }
        random.setSeed(42);
        for (BakedQuad quad : model.getQuads(state, null, random)) {
            if (quad.isTinted() && quad.getTintIndex() != lastTintIndex) {
                lastTintIndex = quad.getTintIndex();
                probeTint(blockColors, state, lastTintIndex, tints, probeBiome);
            }
            rasterize(faceOf(quad.getDirection()), quad, quad.isTinted());
        }

        int withQuads = 0;
        for (int d = 0; d < 6; d++) {
            if (hasQuads[d]) {
                withQuads++;
            }
        }
        if (withQuads == 0) {
            // Fluids, special renderers: the particle sprite over each face, tinted as index 0.
            probeTint(blockColors, state, 0, tints, probeBiome);
            for (int d = 0; d < 6; d++) {
                fullFace(d, model.getParticleIcon());
            }
        }

        boolean dependsOnBiome = false;
        float[] meanU = new float[3];
        float[] meanT = new float[3];
        float meanA = 0;
        int faces = 0;
        int faceKind = -1;
        for (int d = 0; d < 6; d++) {
            if (!hasQuads[d] && withQuads > 0) {
                continue;
            }
            if (meshClass == MaterialClassifier.OPAQUE) {
                fillHoles(d, HOLE_SHADE);
            } else if (meshClass == MaterialClassifier.PARTIAL) {
                // Coarser levels draw a partial block as a full cube (snow over a crown,
                // a slab): above its shape a side takes its own color, not a hole.
                fillHoles(d, 1f);
            }
            dependsOnBiome |= writeFace(off, d);
            int c = off + d * 3;
            float a = (colors[c] >>> 24) / 255f;
            for (int i = 0; i < 3; i++) {
                meanU[i] += FaceTextures.linear(colors[c] >> (i * 8)) * a;
                meanT[i] += FaceTextures.linear(colors[c + 1] >> (i * 8)) * a;
            }
            meanA += a;
            faces++;
            if (kinds[d] >= 0) {
                faceKind = kinds[d];
            }
        }
        // Faces without quads (partial models) take the mean of the others, untextured.
        for (int d = 0; d < 6 && withQuads > 0 && withQuads < 6; d++) {
            if (hasQuads[d]) {
                continue;
            }
            float scale = meanA > 0 ? 1 / meanA : 0;
            int c = off + d * 3;
            colors[c] = abgrLinear(meanU, scale, Math.round(meanA / faces * 255));
            boolean anyTint = faceKind >= 0 && (meanT[0] > 0 || meanT[1] > 0 || meanT[2] > 0);
            colors[c + 1] = anyTint ? abgrLinear(meanT, scale, faceKind) : 0;
            colors[c + 2] = 0;
        }
        return dependsOnBiome;
    }

    /**
     * A plant's look (ADR-059): the sprite of its model's first quad, whole,
     * on face 0, where {@code lod.vert} reads both diagonals of the cross:
     * the sprite's height along the face's columns (U, up) and its width
     * along the rows (V, along the diagonal). Its holes stay (cutout); the
     * other faces copy it. Models without quads use their particle sprite.
     */
    private boolean buildPlant(BlockState state, BakedModel model, BlockColors blockColors, int off,
                               BiomeTints tints, Biome probeBiome) {
        random.setSeed(42);
        List<BakedQuad> quads = model.getQuads(state, null, random);
        TextureAtlasSprite sprite = quads.isEmpty() ? model.getParticleIcon() : quads.get(0).getSprite();
        boolean isTinted = !quads.isEmpty() && quads.get(0).isTinted();
        if (isTinted) {
            probeTint(blockColors, state, quads.get(0).getTintIndex(), tints, probeBiome);
        } else {
            tintKind = -1;
        }
        hasQuads[0] = true;
        // Column a (up) reads the sprite from its bottom row (v1) to its top (v0), row b along u.
        layer(0, sprite, isTinted, 0, 1, 0, 1, sprite.getU0(), sprite.getV1(),
                0, sprite.getV0() - sprite.getV1(), sprite.getU1() - sprite.getU0(), 0);
        boolean dependsOnBiome = writeFace(off, 0);
        for (int d = 1; d < 6; d++) {
            System.arraycopy(colors, off, colors, off + d * 3, 3);
        }
        return dependsOnBiome;
    }

    /**
     * Fills a face's holes with its own mean color times {@code shade}:
     * darkened for opaque materials (inner leaves), as is for partial blocks.
     */
    private void fillHoles(int d, float shade) {
        float[] u = untinted[d];
        float[] t = tintedPart[d];
        float[] a = coverage[d];
        float sumA = 0;
        float[] meanU = new float[3];
        float[] meanT = new float[3];
        for (int i = 0; i < PIXELS; i++) {
            sumA += a[i];
            for (int c = 0; c < 3; c++) {
                meanU[c] += u[i * 3 + c];
                meanT[c] += t[i * 3 + c];
            }
        }
        if (sumA <= 0 || sumA >= PIXELS - 0.01f) {
            return;
        }
        for (int c = 0; c < 3; c++) {
            meanU[c] = meanU[c] / sumA * shade;
            meanT[c] = meanT[c] / sumA * shade;
        }
        for (int i = 0; i < PIXELS; i++) {
            float hole = 1 - a[i];
            for (int c = 0; c < 3; c++) {
                u[i * 3 + c] += meanU[c] * hole;
                t[i * 3 + c] += meanT[c] * hole;
            }
            a[i] = 1;
        }
    }

    /**
     * Writes a baked face: its mean colors and its tiles. Returns whether
     * its look depends on the biome.
     */
    private boolean writeFace(int off, int d) {
        float[] u = untinted[d];
        float[] t = tintedPart[d];
        float[] a = coverage[d];
        float sumA = 0;
        float[] meanU = new float[3];
        float[] meanT = new float[3];
        boolean anyTint = false;
        for (int i = 0; i < PIXELS; i++) {
            sumA += a[i];
            for (int c = 0; c < 3; c++) {
                meanU[c] += u[i * 3 + c];
                meanT[c] += t[i * 3 + c];
                anyTint |= t[i * 3 + c] > 0;
            }
        }
        anyTint &= kinds[d] >= 0;
        float scale = sumA > 0 ? 1 / sumA : 0;
        int c = off + d * 3;
        colors[c] = abgrLinear(meanU, scale, Math.round(sumA / PIXELS * 255));
        colors[c + 1] = anyTint ? abgrLinear(meanT, scale, kinds[d]) : 0;

        int untintedTile = tile(u, a);
        int tintedTile = anyTint && untintedTile >= 0 ? tile(t, a) : -1;
        colors[c + 2] = untintedTile < 0 ? 0 : (untintedTile + 1) | (tintedTile < 0 ? 0 : (tintedTile + 1) << 16);
        if (untintedTile >= 0) {
            for (int i = 0; i < PIXELS; i++) {
                if (a[i] < 0.5f) {
                    colors[c + 1] |= CUTOUT; // the texture has holes the opaque pass cuts out
                    break;
                }
            }
        }
        return anyTint;
    }

    /** Adds the un-premultiplied tile of a premultiplied layer; -1 if the atlas is full. */
    private int tile(float[] premultiplied, float[] alpha) {
        for (int i = 0; i < PIXELS; i++) {
            float s = alpha[i] > 0 ? 1 / alpha[i] : 0;
            for (int c = 0; c < 3; c++) {
                tileRgb[i * 3 + c] = premultiplied[i * 3 + c] * s;
            }
            tileAlpha[i] = alpha[i];
        }
        return textures.add(tileRgb, tileAlpha);
    }

    private static int faceOf(Direction direction) {
        for (int d = 0; d < 6; d++) {
            if (DIRECTIONS[d] == direction) {
                return d;
            }
        }
        return 3;
    }

    /**
     * Projects a quad onto its face's grid and layers its sprite over the
     * pixels it covers ("over" on premultiplied colors): into the tinted part
     * when the biome tints it, else the untinted part (a constant tint baked in).
     */
    private void rasterize(int face, BakedQuad quad, boolean isTinted) {
        int[] v = quad.getVertices();
        int stride = v.length / 4;
        int axis = face >> 1;
        int ua = (axis + 1) % 3;
        int va = (axis + 2) % 3;
        float[] pa = new float[4];
        float[] pb = new float[4];
        float[] pu = new float[4];
        float[] pv = new float[4];
        float minA = Float.MAX_VALUE;
        float maxA = -Float.MAX_VALUE;
        float minB = Float.MAX_VALUE;
        float maxB = -Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            int base = k * stride;
            pa[k] = Float.intBitsToFloat(v[base + ua]);
            pb[k] = Float.intBitsToFloat(v[base + va]);
            pu[k] = Float.intBitsToFloat(v[base + 4]);
            pv[k] = Float.intBitsToFloat(v[base + 5]);
            minA = Math.min(minA, pa[k]);
            maxA = Math.max(maxA, pa[k]);
            minB = Math.min(minB, pb[k]);
            maxB = Math.max(maxB, pb[k]);
        }
        hasQuads[face] = true;
        if (maxA - minA < 1e-4f || maxB - minB < 1e-4f) {
            return;
        }
        int i00 = nearest(pa, pb, minA, minB);
        int i10 = nearest(pa, pb, maxA, minB);
        int i01 = nearest(pa, pb, minA, maxB);
        layer(face, quad.getSprite(), isTinted, minA, maxA, minB, maxB,
                pu[i00], pv[i00], pu[i10] - pu[i00], pv[i10] - pv[i00], pu[i01] - pu[i00], pv[i01] - pv[i00]);
    }

    /** The sprite over the whole face, its UVs along the face's axes. */
    private void fullFace(int face, TextureAtlasSprite sprite) {
        hasQuads[face] = true;
        layer(face, sprite, true, 0, 1, 0, 1, sprite.getU0(), sprite.getV0(),
                sprite.getU1() - sprite.getU0(), 0, 0, sprite.getV1() - sprite.getV0());
    }

    private static int nearest(float[] pa, float[] pb, float a, float b) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            float d = Math.abs(pa[k] - a) + Math.abs(pb[k] - b);
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    /**
     * Layers the sprite over the grid pixels inside {@code [minA, maxA] x
     * [minB, maxB]} (block units along the face's U and V axes), with atlas
     * UVs {@code uv00 + fa * dUvA + fb * dUvB} for the fractions fa, fb of
     * the rectangle.
     */
    private void layer(int face, TextureAtlasSprite sprite, boolean isTinted, float minA, float maxA, float minB,
                       float maxB, float u00, float v00, float duA, float dvA, float duB, float dvB) {
        Sprite s = sprites.computeIfAbsent(sprite, MaterialColors::load);
        if (s.width == 0) {
            return;
        }
        boolean biome = isTinted && tintKind >= 0;
        float[] target = biome ? tintedPart[face] : untinted[face];
        float[] other = biome ? untinted[face] : tintedPart[face];
        float[] cov = coverage[face];
        float su0 = sprite.getU0();
        float sv0 = sprite.getV0();
        float sdu = sprite.getU1() - su0;
        float sdv = sprite.getV1() - sv0;
        if (sdu == 0 || sdv == 0) {
            return;
        }
        // Source pixels per grid pixel for textures above 16 px (box filter).
        int box = Math.max(1, Math.round(s.width / (float) T * (maxA - minA)));
        float[] sample = new float[4];
        for (int y = 0; y < T; y++) {
            float b = (y + 0.5f) / T;
            if (b < minB || b > maxB) {
                continue;
            }
            float fb = (b - minB) / (maxB - minB);
            for (int x = 0; x < T; x++) {
                float a = (x + 0.5f) / T;
                if (a < minA || a > maxA) {
                    continue;
                }
                float fa = (a - minA) / (maxA - minA);
                float u = u00 + fa * duA + fb * duB;
                float v = v00 + fa * dvA + fb * dvB;
                sample(s, (u - su0) / sdu, (v - sv0) / sdv, box, sample);
                float alpha = sample[3];
                if (alpha <= 0) {
                    continue;
                }
                int p = y * T + x;
                for (int c = 0; c < 3; c++) {
                    float value = sample[c];
                    if (isTinted && !biome) {
                        value *= tintConstant[c];
                    }
                    target[p * 3 + c] = alpha * value + (1 - alpha) * target[p * 3 + c];
                    other[p * 3 + c] *= 1 - alpha;
                }
                cov[p] = alpha + (1 - alpha) * cov[p];
            }
        }
        if (biome && kinds[face] < 0) {
            kinds[face] = tintKind;
        }
    }

    /** Alpha-weighted mean of a box of sprite pixels around (s, t) in 0..1 of the sprite. */
    private static void sample(Sprite s, float fs, float ft, int box, float[] out) {
        int cx = (int) Math.floor(fs * s.width - box / 2f + 0.5f);
        int cy = (int) Math.floor(ft * s.height - box / 2f + 0.5f);
        float r = 0;
        float g = 0;
        float b = 0;
        float a = 0;
        for (int dy = 0; dy < box; dy++) {
            int y = Math.max(0, Math.min(s.height - 1, cy + dy));
            for (int dx = 0; dx < box; dx++) {
                int x = Math.max(0, Math.min(s.width - 1, cx + dx));
                int i = y * s.width + x;
                float w = s.alpha[i];
                r += s.rgb[i * 3] * w;
                g += s.rgb[i * 3 + 1] * w;
                b += s.rgb[i * 3 + 2] * w;
                a += w;
            }
        }
        out[3] = a / (box * box);
        if (a > 0) {
            out[0] = r / a;
            out[1] = g / a;
            out[2] = b / a;
        } else {
            out[0] = out[1] = out[2] = 0;
        }
    }

    /** Frame 0 of a sprite (animated sprites hold their frames side by side in the image). */
    private static Sprite load(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        NativeImage image = ((SpriteContentsAccessor) (Object) contents).farfield$originalImage();
        int w = Math.min(contents.width(), image.getWidth());
        int h = Math.min(contents.height(), image.getHeight());
        float[] rgb = new float[w * h * 3];
        float[] alpha = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = image.getPixelRGBA(x, y); // ABGR
                int i = y * w + x;
                rgb[i * 3] = FaceTextures.linear(p);
                rgb[i * 3 + 1] = FaceTextures.linear(p >> 8);
                rgb[i * 3 + 2] = FaceTextures.linear(p >> 16);
                alpha[i] = (p >>> 24) / 255f;
            }
        }
        return new Sprite(w, h, rgb, alpha);
    }

    /**
     * Sets {@link #tintKind} to the kind of a biome-dependent block color, or
     * -1 and {@link #tintConstant} to a constant one (white if none), linear.
     */
    private void probeTint(BlockColors blockColors, BlockState state, int tintIndex, BiomeTints tints,
                           Biome probeBiome) {
        tintKind = -1;
        int color;
        try {
            probe.reset(state, probeBiome);
            color = blockColors.getColor(state, probe, BlockPos.ZERO, tintIndex);
            ColorResolver resolver = probe.resolver();
            // Only a color that is the resolver's own output can be redone per biome.
            if (resolver != null && (color & 0xFFFFFF) == (resolver.getColor(probeBiome, 0, 0) & 0xFFFFFF)) {
                tintKind = tints.kindOf(resolver);
            }
        } catch (RuntimeException e) {
            try {
                color = blockColors.getColor(state, null, null, tintIndex);
            } catch (RuntimeException e2) {
                color = -1;
            }
        }
        tintConstant[0] = FaceTextures.linear(color >> 16);
        tintConstant[1] = FaceTextures.linear(color >> 8);
        tintConstant[2] = FaceTextures.linear(color);
    }

    private static int mapColor(BlockState state) {
        try {
            MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return color == null || color == MapColor.NONE ? FALLBACK : abgr(color.col, 0xFF);
        } catch (RuntimeException e) {
            return FALLBACK;
        }
    }

    /** ABGR8 of premultiplied linear RGB scaled back by {@code scale}, encoded to sRGB, with an alpha byte. */
    private static int abgrLinear(float[] rgb, float scale, int alpha) {
        int r = srgb(rgb[0] * scale);
        int g = srgb(rgb[1] * scale);
        int b = srgb(rgb[2] * scale);
        return (alpha & 0xFF) << 24 | b << 16 | g << 8 | r;
    }

    private static int srgb(float linear) {
        return Math.round((float) Math.pow(Math.min(1f, Math.max(0f, linear)), 1 / 2.2) * 255);
    }

    static int abgr(int rgb, int alpha) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (alpha & 0xFF) << 24 | b << 16 | g << 8 | r;
    }
}
