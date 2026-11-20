package io.lakio.farfield.api.client;

/**
 * What Farfield drew this frame, for mods that draw over the far terrain:
 * clouds, sky, fog, weather.
 *
 * <p>Read it on the render thread, after Farfield drew its LODs (after the sky,
 * before the game's terrain) and before the end of the frame: a mod drawing
 * its clouds or a pass at the end of the level reads this frame's values.
 * {@link FarfieldClientApi#lodFrame()} gives an inactive frame when Farfield
 * drew nothing (no LODs yet, rendering off, a shader pack in charge), or when
 * no mod asked for the depth ({@link FarfieldClientApi#requestLodDepth}).
 *
 * <p>The depth holds the LODs alone. The game's terrain is not in it: near the
 * player the LODs give way to the game's chunks, so read the game's own depth
 * first and fall back to this one where the game drew nothing. Translucent LOD
 * surfaces (water) write no depth: the depth there is the ground below them.
 */
public interface LodFrame {

    /** True when the other values describe this frame. */
    boolean active();

    /**
     * The OpenGL name of a 2D depth texture the size of the game's main render
     * target, sampled as a float in {@code [0, 1]} ({@code texture(sampler, uv).r}).
     * Where no LOD was drawn it holds the far value: 1, or 0 when
     * {@link #reverseZ()}. Farfield owns it: sample it, never write to it or
     * delete it; it is recreated when the window size changes.
     */
    int depthTexture();

    /** True when nearer surfaces have larger depth values (far is 0). */
    boolean reverseZ();

    /**
     * True when normalized device depth is the texture value itself; false
     * when it is {@code value * 2 - 1} (OpenGL's default clip range).
     */
    boolean zeroToOne();

    /**
     * Copies the inverse of the LODs' view-projection into {@code dest}
     * (16 floats, column-major, as {@code glUniformMatrix4fv} takes them with
     * transpose false). It maps a normalized device position
     * {@code (uv * 2 - 1, ndcDepth, 1)} to a position relative to the camera,
     * in blocks, after the division by w. Only {@link #depthTexture()}'s depth
     * fits this matrix: the game's depth uses the game's projection.
     */
    void inverseViewProjection(float[] dest);

    /** The distance of the farthest LOD geometry drawn, in blocks from the camera. */
    float viewDistance();
}
