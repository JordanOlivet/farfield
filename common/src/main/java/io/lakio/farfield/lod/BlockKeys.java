package io.lakio.farfield.lod;

import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.world.MaterialTable;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
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
        BlockState state = state(key);
        if (state == null) {
            return MaterialTable.pack(MaterialClassifier.OPAQUE, MipBuilder.Shapes.FULL);
        }
        return resolve(state);
    }

    static int resolve(BlockState state) {
        if (state.isAir()) {
            return MaterialTable.pack(MaterialClassifier.AIR, MipBuilder.Shapes.EMPTY);
        }
        Block block = state.getBlock();
        if (block instanceof LiquidBlock) {
            return MaterialTable.pack(MaterialClassifier.TRANSLUCENT, MipBuilder.Shapes.FULL);
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
        if (Block.isShapeFullBlock(shape)) {
            int meshClass = state.canOcclude() ? MaterialClassifier.OPAQUE : MaterialClassifier.TRANSLUCENT;
            return MaterialTable.pack(meshClass, MipBuilder.Shapes.FULL);
        }
        if (!state.getFluidState().isEmpty()) {
            // Waterlogged partial blocks, seagrass, kelp: the water dominates.
            return MaterialTable.pack(MaterialClassifier.TRANSLUCENT, MipBuilder.Shapes.FULL);
        }
        if (!shape.isEmpty()) {
            AABB b = shape.bounds();
            if (b.minX <= 0.01 && b.maxX >= 0.99 && b.minZ <= 0.01 && b.maxZ >= 0.99) {
                // Lower than a cube and standing on the bottom (snow layers, bottom
                // slabs, carpets): drawn at its own height on the finest level.
                boolean partial = b.minY <= 0.01 && b.maxY < 0.99;
                int meshClass = partial ? MaterialClassifier.PARTIAL : MaterialClassifier.OPAQUE;
                return MaterialTable.pack(meshClass, MipBuilder.Shapes.COVER);
            }
        }
        return MaterialTable.pack(MaterialClassifier.AIR, MipBuilder.Shapes.SPARSE);
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
     * blocks, seagrass, kelp), else the state itself.
     */
    public static BlockState lookOf(BlockState state) {
        Block block = state.getBlock();
        if (state.getFluidState().isEmpty() || block instanceof LiquidBlock || block instanceof LeavesBlock) {
            return state;
        }
        try {
            if (Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) {
                return state;
            }
        } catch (RuntimeException e) {
            return state;
        }
        return state.getFluidState().createLegacyBlock();
    }
}
