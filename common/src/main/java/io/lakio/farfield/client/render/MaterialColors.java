package io.lakio.farfield.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.lod.BlockKeys;
import io.lakio.farfield.mixin.client.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Colors of an LOD world's block states for the shader, from the averaged
 * textures of their models (ADR-010, far levels).
 *
 * <p>Per block-state id, {@link #STRIDE} ints: per face direction (the
 * order of {@code Quad}), an untinted color and a biome-tinted color, both
 * ABGR8 in sRGB. The face's color is {@code untinted + tinted * tint} (summed
 * in linear space), where the tint comes from {@link BiomeTints} for the
 * quad's biome and the kind in the tinted color's alpha byte; the untinted
 * color's alpha is the face's coverage (water, glass, leaves are below 1).
 * Grass blocks thus get a tinted top and dirt sides with a tinted strip.
 *
 * <p>A face averages the sprites of the model's quads on that side, layered
 * in drawing order (the grass overlay over its side), in linear space and
 * weighted by alpha. Constant block colors (birch and spruce leaves) are
 * baked in. Blocks without quads (fluids, blocks drawn by special renderers)
 * take their particle sprite; a failure falls back to the map color.
 *
 * <p>Built on the render thread under a time budget per frame; materials not
 * built yet read as tinted for the mesher threads ({@link #tinted}).
 */
final class MaterialColors {

    static final int STRIDE = 12;

    private static final long BUDGET_NANOS = 3_000_000L;
    private static final int FALLBACK = abgr(0x808080, 0xFF);
    /** Quad direction order: -X, +X, -Y, +Y, -Z, +Z. */
    private static final Direction[] DIRECTIONS = {
            Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
    private static final float[] LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            LINEAR[i] = (float) Math.pow(i / 255.0, 2.2);
        }
    }

    private int[] colors = new int[0];
    private int count;
    /** Published to the mesher threads; replaced, never modified. */
    private volatile boolean[] tinted = new boolean[0];

    private final Map<TextureAtlasSprite, float[]> sprites = new IdentityHashMap<>();
    private final TintProbe probe = new TintProbe();
    private final RandomSource random = RandomSource.create();

    // Per-face accumulation: premultiplied untinted and tinted linear RGB, coverage, tint kind.
    private final float[][] untinted = new float[6][3];
    private final float[][] tintedPart = new float[6][3];
    private final float[] coverage = new float[6];
    private final int[] kinds = new int[6];
    private final boolean[] hasQuads = new boolean[6];
    private int tintKind;
    private int tintConstant;

    /**
     * Builds the colors of new ids within the frame's budget.
     *
     * @param probeBiome the biome block colors are probed in (plains)
     * @return the first id built, or -1 if none
     */
    int update(KeyRegistry registry, BiomeTints tints, Biome probeBiome) {
        int size = registry.size();
        if (size == count) {
            return -1;
        }
        if (colors.length < size * STRIDE) {
            colors = Arrays.copyOf(colors, Math.max(size, colors.length / STRIDE * 2) * STRIDE);
        }
        boolean[] flags = Arrays.copyOf(tinted, size);
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int first = count;
        int id = count;
        while (id < size) {
            flags[id] = build(registry.key(id), id * STRIDE, tints, probeBiome);
            id++;
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        count = id;
        tinted = Arrays.copyOf(flags, id);
        return first;
    }

    /** Forgets every color (resource reload): they are built again. */
    void reset() {
        count = 0;
        sprites.clear();
        tinted = new boolean[0];
    }

    /** Whether a material's quads must carry their biome; true for materials not built yet. Any thread. */
    boolean tinted(int material) {
        boolean[] t = tinted;
        return material >= t.length || t[material];
    }

    int[] colors() {
        return colors;
    }

    int count() {
        return count;
    }

    /** Builds one state's colors at {@code off}; returns whether a face depends on the biome. */
    private boolean build(String key, int off, BiomeTints tints, Biome probeBiome) {
        BlockState state = BlockKeys.state(key);
        if (state == null) {
            Arrays.fill(colors, off, off + STRIDE, 0);
            for (int d = 0; d < 6; d++) {
                colors[off + d * 2] = FALLBACK;
            }
            return false;
        }
        try {
            return build(BlockKeys.lookOf(state), off, tints, probeBiome);
        } catch (RuntimeException | LinkageError e) {
            int color = mapColor(state);
            for (int d = 0; d < 6; d++) {
                colors[off + d * 2] = color;
                colors[off + d * 2 + 1] = 0;
            }
            return false;
        }
    }

    private boolean build(BlockState state, int off, BiomeTints tints, Biome probeBiome) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        BlockColors blockColors = mc.getBlockColors();
        for (int d = 0; d < 6; d++) {
            Arrays.fill(untinted[d], 0);
            Arrays.fill(tintedPart[d], 0);
            coverage[d] = 0;
            kinds[d] = -1;
            hasQuads[d] = false;
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
                layer(d, quad.getSprite(), quad.isTinted());
            }
        }
        random.setSeed(42);
        for (BakedQuad quad : model.getQuads(state, null, random)) {
            if (quad.isTinted() && quad.getTintIndex() != lastTintIndex) {
                lastTintIndex = quad.getTintIndex();
                probeTint(blockColors, state, lastTintIndex, tints, probeBiome);
            }
            layer(faceOf(quad.getDirection()), quad.getSprite(), quad.isTinted());
        }

        // Faces without quads take the mean of the others; a model without
        // any (fluids, special renderers) its particle sprite, tinted as index 0.
        int withQuads = 0;
        for (int d = 0; d < 6; d++) {
            if (hasQuads[d]) {
                withQuads++;
            }
        }
        if (withQuads == 0) {
            probeTint(blockColors, state, 0, tints, probeBiome);
            for (int d = 0; d < 6; d++) {
                layer(d, model.getParticleIcon(), true);
            }
        } else if (withQuads < 6) {
            fillMissingFaces(withQuads);
        }

        boolean dependsOnBiome = false;
        for (int d = 0; d < 6; d++) {
            float a = coverage[d];
            float scale = a > 0 ? 1 / a : 0;
            colors[off + d * 2] = abgrLinear(untinted[d], scale, Math.round(a * 255));
            int kind = kinds[d];
            float[] t = tintedPart[d];
            boolean anyTint = kind >= 0 && (t[0] > 0 || t[1] > 0 || t[2] > 0);
            colors[off + d * 2 + 1] = anyTint ? abgrLinear(t, scale, kind) : 0;
            dependsOnBiome |= anyTint;
        }
        return dependsOnBiome;
    }

    private static int faceOf(Direction direction) {
        for (int d = 0; d < 6; d++) {
            if (DIRECTIONS[d] == direction) {
                return d;
            }
        }
        return 3;
    }

    private void fillMissingFaces(int withQuads) {
        float[] u = new float[3];
        float[] t = new float[3];
        float a = 0;
        int kind = -1;
        for (int d = 0; d < 6; d++) {
            if (hasQuads[d]) {
                for (int c = 0; c < 3; c++) {
                    u[c] += untinted[d][c] / withQuads;
                    t[c] += tintedPart[d][c] / withQuads;
                }
                a += coverage[d] / withQuads;
                if (kinds[d] >= 0) {
                    kind = kinds[d];
                }
            }
        }
        for (int d = 0; d < 6; d++) {
            if (!hasQuads[d]) {
                System.arraycopy(u, 0, untinted[d], 0, 3);
                System.arraycopy(t, 0, tintedPart[d], 0, 3);
                coverage[d] = a;
                kinds[d] = kind;
            }
        }
    }

    /**
     * Layers a quad's averaged sprite over a face ("over" operator on
     * premultiplied colors): the tinted part when the quad is tinted by the
     * biome, else the untinted part (with a constant tint baked in).
     */
    private void layer(int face, TextureAtlasSprite sprite, boolean isTinted) {
        float[] avg = sprites.computeIfAbsent(sprite, MaterialColors::average);
        float alpha = avg[3];
        if (alpha <= 0) {
            hasQuads[face] = true;
            return;
        }
        boolean biome = isTinted && tintKind >= 0;
        float[] target = biome ? tintedPart[face] : untinted[face];
        float[] other = biome ? untinted[face] : tintedPart[face];
        for (int c = 0; c < 3; c++) {
            float value = avg[c];
            if (isTinted && !biome) {
                value *= LINEAR[tintConstant >> (16 - c * 8) & 0xFF];
            }
            target[c] = alpha * value + (1 - alpha) * target[c];
            other[c] *= 1 - alpha;
        }
        coverage[face] = alpha + (1 - alpha) * coverage[face];
        if (biome && kinds[face] < 0) {
            kinds[face] = tintKind;
        }
        hasQuads[face] = true;
    }

    /**
     * Sets {@link #tintKind} to the kind of a biome-dependent block color, or
     * -1 and {@link #tintConstant} to a constant one (white if none).
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
        tintConstant = color & 0xFFFFFF;
    }

    /** Alpha-weighted mean linear RGB of a sprite's pixels (all frames), and its mean alpha. */
    private static float[] average(TextureAtlasSprite sprite) {
        NativeImage image = ((SpriteContentsAccessor) (Object) sprite.contents()).farfield$originalImage();
        double r = 0;
        double g = 0;
        double b = 0;
        double a = 0;
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = image.getPixelRGBA(x, y); // ABGR
                double alpha = (p >>> 24) / 255.0;
                r += LINEAR[p & 0xFF] * alpha;
                g += LINEAR[p >> 8 & 0xFF] * alpha;
                b += LINEAR[p >> 16 & 0xFF] * alpha;
                a += alpha;
            }
        }
        if (a <= 0) {
            return new float[4];
        }
        return new float[]{(float) (r / a), (float) (g / a), (float) (b / a), (float) (a / (width * height))};
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
