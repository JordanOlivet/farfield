package io.lakio.farfield.compat;

//? if <1.21.11 {
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
//?}

/**
 * The game's terrain fog as the LODs read it, across Minecraft versions
 * (ADR-165): up to 1.21.5 the shader fog of {@code RenderSystem}, which
 * {@code FogRendererMixin} moves out; since, the fog the game uploads each
 * frame (environmental and render distance fog), handed here by the same
 * mixin after it moved it.
 */
public final class GameFog {
    //? if >=1.21.11 {
    /*private static float start = Float.MAX_VALUE;
    private static float end = Float.MAX_VALUE;
    private static final float[] COLOR = new float[4];

    // Set by the fog mixin with this frame's fog, after it moved it.
    public static void set(float fogStart, float fogEnd, float red, float green, float blue, float alpha) {
        start = fogStart;
        end = fogEnd;
        COLOR[0] = red;
        COLOR[1] = green;
        COLOR[2] = blue;
        COLOR[3] = alpha;
    }
    *///?}

    private GameFog() {
    }

    public static float start() {
        //? if >=1.21.11 {
        /*return start;
        *///?} else {
        return RenderSystem.getShaderFogStart();
        //?}
    }

    public static float end() {
        //? if >=1.21.11 {
        /*return end;
        *///?} else {
        return RenderSystem.getShaderFogEnd();
        //?}
    }

    /** The fog's color, red, green, blue and alpha; not to be kept. */
    public static float[] color() {
        //? if >=1.21.11 {
        /*return COLOR;
        *///?} else {
        return RenderSystem.getShaderFogColor();
        //?}
    }

    /** True when the fog is cylindrical (vanilla's terrain fog up to 1.21.5); spherical since. */
    public static boolean cylinder() {
        //? if >=1.21.11 {
        /*return false;
        *///?} else {
        return RenderSystem.getShaderFogShape() == FogShape.CYLINDER;
        //?}
    }
}
