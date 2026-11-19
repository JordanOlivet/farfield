package io.lakio.farfield.compat;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
//? if >=1.21.11 {
/*import java.util.ArrayList;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.model.geom.builders.UVPair;
*///?}

/**
 * The block models the material looks are baked from, across Minecraft
 * versions (ADR-165): up to 1.21.4 a block state's {@code BakedModel} gives
 * its quads; since 1.21.5 a {@code BlockStateModel} gives parts, each with
 * its quads, and a quad is a record of its four positions and UVs; since
 * 26.1 the model set of the model manager gives the models, and a quad's
 * sprite and tint sit in its material info.
 */
public final class Models {
    private Models() {
    }

    /** The quads of {@code state}'s model on {@code side} ({@code null}: the unculled ones). */
    public static List<BakedQuad> quads(BlockState state, @Nullable Direction side, RandomSource random) {
        return quads(state, state, side, random);
    }

    /** The quads of {@code modelState}'s model, as drawn for {@code state}, on {@code side}. */
    public static List<BakedQuad> quads(BlockState modelState, BlockState state, @Nullable Direction side,
                                        RandomSource random) {
        //? if >=26.1 {
        /*BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(modelState);
        List<BlockModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts);
        *///?} else if >=1.21.11 {
        /*BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(modelState);
        List<BlockModelPart> parts = model.collectParts(random);
        *///?}
        //? if >=1.21.11 {
        /*if (parts.size() == 1) {
            return parts.get(0).getQuads(side);
        }
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockModelPart part : parts) {
            quads.addAll(part.getQuads(side));
        }
        return quads;
        *///?} else {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(modelState).getQuads(state, side, random);
        //?}
    }

    /** The sprite of {@code state}'s particles (its model's icon). */
    public static TextureAtlasSprite particle(BlockState state) {
        //? if >=26.1 {
        /*return Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
        *///?} else if >=1.21.11 {
        /*return Minecraft.getInstance().getBlockRenderer().getBlockModel(state).particleIcon();
        *///?} else {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state).getParticleIcon();
        //?}
    }

    /** The missing model: a new object after each resource reload. */
    public static Object missingModel() {
        //? if >=26.1 {
        /*return Minecraft.getInstance().getModelManager().getBlockStateModelSet().missingModel();
        *///?} else if >=1.21.11 {
        /*return Minecraft.getInstance().getModelManager().getMissingBlockStateModel();
        *///?} else {
        return Minecraft.getInstance().getModelManager().getMissingModel();
        //?}
    }

    /** The missing model's particle sprite (the magenta and black checker). */
    public static TextureAtlasSprite missingSprite() {
        //? if >=26.1 {
        /*return Minecraft.getInstance().getModelManager().getBlockStateModelSet().missingModel().particleMaterial().sprite();
        *///?} else if >=1.21.11 {
        /*return Minecraft.getInstance().getModelManager().getMissingBlockStateModel().particleIcon();
        *///?} else {
        return Minecraft.getInstance().getModelManager().getMissingModel().getParticleIcon();
        //?}
    }

    public static int tintIndex(BakedQuad quad) {
        //? if >=26.1 {
        /*return quad.materialInfo().tintIndex();
        *///?} else if >=1.21.11 {
        /*return quad.tintIndex();
        *///?} else {
        return quad.getTintIndex();
        //?}
    }

    public static boolean isTinted(BakedQuad quad) {
        //? if >=26.1 {
        /*return quad.materialInfo().isTinted();
        *///?} else {
        return quad.isTinted();
        //?}
    }

    public static TextureAtlasSprite sprite(BakedQuad quad) {
        //? if >=26.1 {
        /*return quad.materialInfo().sprite();
        *///?} else if >=1.21.11 {
        /*return quad.sprite();
        *///?} else {
        return quad.getSprite();
        //?}
    }

    public static Direction direction(BakedQuad quad) {
        //? if >=1.21.11 {
        /*return quad.direction();
        *///?} else {
        return quad.getDirection();
        //?}
    }

    /** Coordinate {@code axis} (0 x, 1 y, 2 z) of vertex {@code k}, in the block (0 to 1). */
    public static float position(BakedQuad quad, int k, int axis) {
        //? if >=1.21.11 {
        /*var p = quad.position(k);
        return axis == 0 ? p.x() : axis == 1 ? p.y() : p.z();
        *///?} else {
        return Float.intBitsToFloat(quad.getVertices()[k * (quad.getVertices().length / 4) + axis]);
        //?}
    }

    /** Atlas U of vertex {@code k}. */
    public static float u(BakedQuad quad, int k) {
        //? if >=1.21.11 {
        /*return UVPair.unpackU(quad.packedUV(k));
        *///?} else {
        return Float.intBitsToFloat(quad.getVertices()[k * (quad.getVertices().length / 4) + 4]);
        //?}
    }

    /** Atlas V of vertex {@code k}. */
    public static float v(BakedQuad quad, int k) {
        //? if >=1.21.11 {
        /*return UVPair.unpackV(quad.packedUV(k));
        *///?} else {
        return Float.intBitsToFloat(quad.getVertices()[k * (quad.getVertices().length / 4) + 5]);
        //?}
    }
}
