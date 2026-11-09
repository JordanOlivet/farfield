package io.lakio.farfield.client.render;

import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.BlockKeys;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.IntPredicate;

/**
 * The kind of a world's feature blocks, by material id, for the preview's
 * choice of features (plan item D.3): the tiles keep the features as
 * blocks, so a tree is told by its blocks. Trees: logs and stems, leaves,
 * nether wart blocks and shroomlights, huge mushroom blocks, mangrove
 * roots and bee nests (tags, not a list of mods, so mods' trees count when
 * they tag their logs and leaves). Covers: snow and ice the world's freeze
 * feature lays on top of whatever is there, which follow the block under
 * them. Answers are kept per id; mesher threads call it.
 */
final class TreeMaterials {

    private static final byte UNKNOWN = 0;
    private static final byte TREE = 1;
    private static final byte COVER = 2;
    private static final byte OTHER = 3;

    private final LodWorld world;
    /** Per id, {@link #UNKNOWN} until asked; a race only computes an answer twice. */
    private volatile byte[] known = new byte[0];
    private final IntPredicate tree = id -> kind(id) == TREE;
    private final IntPredicate cover = id -> kind(id) == COVER;

    TreeMaterials(LodWorld world) {
        this.world = world;
    }

    LodWorld world() {
        return world;
    }

    IntPredicate tree() {
        return tree;
    }

    IntPredicate cover() {
        return cover;
    }

    private byte kind(int id) {
        if (id < 0) {
            return OTHER;
        }
        byte[] k = known;
        if (id < k.length && k[id] != UNKNOWN) {
            return k[id];
        }
        String key = world.blockStates().key(id);
        BlockState state = key == null ? null : BlockKeys.state(key);
        byte kind = state == null ? OTHER : isTree(state) ? TREE : isCover(state) ? COVER : OTHER;
        synchronized (this) {
            byte[] now = known;
            if (id >= now.length) {
                now = java.util.Arrays.copyOf(now, Math.max(id + 1, now.length * 2));
            }
            now[id] = kind;
            known = now;
        }
        return kind;
    }

    static boolean isTree(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.WART_BLOCKS)
                || state.getBlock() instanceof HugeMushroomBlock || state.is(Blocks.SHROOMLIGHT)
                || state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.BEE_NEST);
    }

    static boolean isCover(BlockState state) {
        return state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.ICE);
    }
}
