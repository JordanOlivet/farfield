package io.lakio.farfield.lod;

import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.ShapeBoxes;
import io.lakio.farfield.core.preview.PreviewVoxels;
import io.lakio.farfield.core.world.CoveredKey;
import io.lakio.farfield.core.world.MaterialTable;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical string keys of block states and biomes (the same as the region
 * importer's: {@code name[k=v,...]}, properties sorted by name), and the
 * engine's material properties of a block state.
 */
public final class BlockKeys {

    private static final Map<BlockState, String> STATE_KEYS = new ConcurrentHashMap<>();
    private static final Map<String, BlockState> STATES_BY_KEY = new ConcurrentHashMap<>();

    /** {@link #resolve(String)} and {@link #boxes(String)}, for a world's material table. */
    public static final MaterialTable.Resolver RESOLVER = new MaterialTable.Resolver() {
        @Override
        public int resolve(String key) {
            return BlockKeys.resolve(key);
        }

        @Override
        public int[] boxes(String key) {
            BlockState state = state(key);
            return state == null ? null : BlockKeys.boxes(state);
        }

        @Override
        public String fluid(String key) {
            BlockState state = state(key);
            return state == null || state.getFluidState().isEmpty() ? null
                    : BlockKeys.key(state.getFluidState().createLegacyBlock());
        }
    };

    private BlockKeys() {
    }

    public static String key(BlockState state) {
        String key = STATE_KEYS.get(state);
        if (key == null) {
            key = buildKey(state);
            STATE_KEYS.put(state, key);
            STATES_BY_KEY.putIfAbsent(key, state);
        }
        return key;
    }

    private static String buildKey(BlockState state) {
        StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        if (!properties.isEmpty()) {
            properties.sort(Comparator.comparing(Property::getName));
            sb.append('[');
            for (int i = 0; i < properties.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Property<?> p = properties.get(i);
                sb.append(p.getName()).append('=').append(valueName(state, p));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    public static String key(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.location().toString()).orElse("farfield:unknown");
    }

    /** The block state of a key, or null if it no longer exists (removed mod). */
    public static BlockState state(String key) {
        CoveredKey cover = CoveredKey.parse(key);
        if (cover != null) {
            return state(cover.ground()); // a ground with plants on it is its ground (ADR-060)
        }
        BlockState state = STATES_BY_KEY.get(key);
        if (state != null) {
            return state;
        }
        try {
            state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), key, false).blockState();
        } catch (Exception e) {
            return null;
        }
        STATES_BY_KEY.putIfAbsent(key, state);
        return state;
    }

    /** {@link MaterialTable.Resolver} from block states; unknown keys are opaque cubes. */
    public static int resolve(String key) {
        if (key.equals(PreviewVoxels.MASK_FLUID_KEY)) {
            // The preview's real-fluid mask: hides preview water faces, not land ones.
            return MaterialTable.packFluid();
        }
        BlockState state = state(key);
        if (state == null) {
            return MaterialTable.pack(MaterialClassifier.OPAQUE, MipBuilder.Shapes.FULL);
        }
        return resolve(state);
    }

    /** Whether LODs draw a state as a plant's crossed quads ({@link MaterialClassifier#PLANT}). */
    public static boolean isPlant(BlockState state) {
        return meshClass(state) == MaterialClassifier.PLANT;
    }

    /**
     * How LODs draw a state ({@link MaterialClassifier}'s classes; {@code AIR}
     * for what they leave out, torches and rails), {@code AIR} when it
     * cannot be told.
     */
    public static int meshClass(BlockState state) {
        try {
            return resolve(state) & 15;
        } catch (RuntimeException e) {
            return MaterialClassifier.AIR;
        }
    }

    static int resolve(BlockState state) {
        if (state.isAir()) {
            return MaterialTable.pack(MaterialClassifier.AIR, MipBuilder.Shapes.EMPTY);
        }
        Block block = state.getBlock();
        if (block instanceof LiquidBlock) {
            return MaterialTable.packFluid();
        }
        if (block instanceof LeavesBlock) {
            return MaterialTable.pack(MaterialClassifier.OPAQUE, MipBuilder.Shapes.FULL);
        }
        VoxelShape shape;
        try {
            // Some modded blocks need a real level for their shape; treat a failure as a cube.
            shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return MaterialTable.pack(MaterialClassifier.OPAQUE, MipBuilder.Shapes.FULL);
        }
        if (!state.getFluidState().isEmpty() && !(Block.isShapeFullBlock(shape) && state.canOcclude())) {
            // Waterlogged partial blocks, seagrass, kelp: the water dominates, and they are
            // that water for the mesher too: no faces between them and the water around
            // (dark squares around kelp once ice and water showed their faces, ADR-055).
            // Before the full-block test: the kelp's body has a full outline (ADR-063).
            // Kelp and seagrass also show their crossed quads in that water (ADR-065).
            return isSubmergedPlant(state) ? MaterialTable.packSubmerged() : MaterialTable.packFluid();
        }
        if (isVegetation(block) && !shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) >= 0.5
                && !hasCollision(state)) {
            // Before the full-block test: a double plant's outline is a whole block (tall grass,
            // sunflowers, lilacs), and they showed as see-through cubes (ADR-080). Flat ones
            // (pink petals, clover) stay low covers below.
            return MaterialTable.pack(MaterialClassifier.PLANT, MipBuilder.Shapes.SPARSE);
        }
        if (Block.isShapeFullBlock(shape)) {
            int meshClass = state.canOcclude() ? MaterialClassifier.OPAQUE : MaterialClassifier.TRANSLUCENT;
            return MaterialTable.pack(meshClass, MipBuilder.Shapes.FULL);
        }
        if (!shape.isEmpty()) {
            AABB b = shape.bounds();
            if (b.minX <= 0.01 && b.maxX >= 0.99 && b.minZ <= 0.01 && b.maxZ >= 0.99) {
                if (b.minY <= 0.01 && b.maxY < 0.99) {
                    // Lower than a cube and standing on the bottom (snow layers, bottom
                    // slabs, carpets): drawn at its own height on the finest level.
                    return MaterialTable.pack(MaterialClassifier.PARTIAL, MipBuilder.Shapes.COVER,
                            heightSixteenths(state));
                }
                // Stairs, top slabs: their boxes on the finest level.
                return MaterialTable.pack(MaterialClassifier.SHAPED, MipBuilder.Shapes.COVER, 16);
            }
            if (hasCollision(state)) {
                // Fences, walls, panes, doors: their boxes on the finest level. Without
                // a collision, plants are crossed quads there (ADR-059); torches and
                // rails stay out of the LODs.
                return MaterialTable.pack(MaterialClassifier.SHAPED, MipBuilder.Shapes.SPARSE, 0);
            }
            if (isVegetation(block)) {
                return MaterialTable.pack(MaterialClassifier.PLANT, MipBuilder.Shapes.SPARSE);
            }
        }
        return MaterialTable.pack(MaterialClassifier.AIR, MipBuilder.Shapes.SPARSE);
    }

    /**
     * Blocks that grow as plants: bushes, flowers, grass, sugar cane, and
     * those that grow like kelp or vines (Biomes O' Plenty's high grass,
     * twisting vines), which the LODs left out before ADR-080.
     */
    private static boolean isVegetation(Block block) {
        return block instanceof BushBlock || block instanceof SugarCaneBlock || block instanceof GrowingPlantBlock;
    }

    /** A plant without collision standing in its fluid: kelp, seagrass, a waterlogged propagule. */
    public static boolean isSubmergedPlant(BlockState state) {
        Block block = state.getBlock();
        return !state.getFluidState().isEmpty() && !hasCollision(state)
                && (block instanceof BushBlock || block instanceof GrowingPlantBlock);
    }

    private static boolean hasCollision(BlockState state) {
        try {
            return !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The boxes of a block's shape in sixteenths ({@link ShapeBoxes}), the
     * {@link ShapeBoxes#MAX} largest when it has more; null without a usable shape.
     */
    public static int[] boxes(BlockState state) {
        List<AABB> parts;
        try {
            parts = new ArrayList<>(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs());
        } catch (RuntimeException e) {
            return null;
        }
        if (parts.size() > ShapeBoxes.MAX) {
            parts.sort(Comparator.comparingDouble((AABB a) -> a.getXsize() * a.getYsize() * a.getZsize()).reversed());
        }
        int n = Math.min(parts.size(), ShapeBoxes.MAX);
        int[] boxes = new int[n];
        for (int i = 0; i < n; i++) {
            AABB a = parts.get(i);
            int minX = Math.min(15, sixteenths(a.minX));
            int minY = Math.min(15, sixteenths(a.minY));
            int minZ = Math.min(15, sixteenths(a.minZ));
            // At least one sixteenth thick, so thin parts keep their faces apart.
            boxes[i] = ShapeBoxes.pack(minX, minY, minZ, Math.max(minX + 1, sixteenths(a.maxX)),
                    Math.max(minY + 1, sixteenths(a.maxY)), Math.max(minZ + 1, sixteenths(a.maxZ)));
        }
        return n == 0 ? null : boxes;
    }

    /** A shape coordinate in sixteenths, 0..15 for a min and up to 16 for a max of a box. */
    private static int sixteenths(double v) {
        return Math.max(0, Math.min(16, (int) Math.round(v * 16)));
    }

    /**
     * Height of a {@link MaterialClassifier#PARTIAL} block in sixteenths of a
     * block (1..16, the top of its shape), 16 when it has no usable shape.
     */
    public static int heightSixteenths(BlockState state) {
        try {
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                return 16;
            }
            return Math.max(1, Math.min(16, (int) Math.round(shape.bounds().maxY * 16)));
        } catch (RuntimeException e) {
            return 16;
        }
    }

    /**
     * The state whose look an LOD voxel of {@code state} takes: its fluid for
     * the blocks {@link #resolve} turns into water (waterlogged partial
     * blocks), else the state itself. Kelp and seagrass keep their own: their
     * material is their crossed quads, the water's faces take the water's.
     */
    public static BlockState lookOf(BlockState state) {
        Block block = state.getBlock();
        if (state.getFluidState().isEmpty() || block instanceof LiquidBlock || block instanceof LeavesBlock
                || isSubmergedPlant(state)) {
            return state;
        }
        try {
            // Only an opaque full block shows itself through its water; the kelp's body
            // (full outline, not opaque) looks like the water it is classed as.
            if (Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) && state.canOcclude()) {
                return state;
            }
        } catch (RuntimeException e) {
            return state;
        }
        return state.getFluidState().createLegacyBlock();
    }
}
