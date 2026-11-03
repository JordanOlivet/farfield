package io.lakio.farfield.client.render;

import org.joml.Matrix4f;

/**
 * The LOD passes under a shader pack (ADR-113): implemented over Iris in
 * {@code client.render.iris}, loaded only when Iris is installed. Render
 * thread only.
 */
public interface ShaderPackLink {

    /** No shader pack in use: the LODs draw as usual. */
    int VANILLA = 0;
    /** A pack in use whose LOD contract Farfield cannot follow: no LODs. */
    int NONE = 1;
    /** Draw through the pack's Voxy contract. */
    int VOXY = 2;

    /**
     * At the start of each level frame, LODs drawn or not: follows a pack
     * change, clears the LOD depth the pack reads and sets its matrices.
     *
     * @param projection   the game's projection this frame
     * @param viewRotation the camera rotation
     * @param near         the LODs' near plane (their own depth range)
     * @param far          the LODs' far plane
     * @param distance     the LOD distance in blocks
     */
    void frame(Matrix4f projection, Matrix4f viewRotation, float near, float far, int distance);

    /**
     * Before the LOD passes: {@link #VANILLA}, {@link #NONE}, or
     * {@link #VOXY} with the pack's LOD target bound.
     */
    int begin();

    /** Bumped each time the pack's sources change: the programs below are built again. */
    int generation();

    /**
     * The pack's opaque fragment code as a shader unit that defines
     * {@code voxy_emitFragment}, or null.
     *
     * @param parameters the declaration of {@code VoxyFragmentParameters}, shared with {@code lod.frag}
     */
    String opaqueUnit(String parameters);

    /**
     * A vertex shader unit that defines {@code vec2 voxy_taaOffset()}, the
     * pack's TAA jitter in NDC, or null when the pack has none: every LOD
     * program under the pack links it, depth pre-pass included.
     */
    String vertexUnit();

    /**
     * The pack's translucent fragment code as a shader unit that defines
     * {@code voxy_emitFragment}, or null: no translucent LODs under this pack.
     */
    String translucentUnit(String parameters);

    /**
     * Connects a linked program to the pack's uniforms and samplers.
     *
     * @param translucent the program draws the translucent LODs (before the
     *                    pack's deferred passes, as the opaque ones)
     */
    void attach(int program, String name, boolean translucent);

    /**
     * After the game's opaque terrain and entities, before the pack's
     * deferred passes (where Voxy draws them): the pack's translucent LOD
     * target bound with its blending, or false.
     */
    boolean beginTranslucent();

    /** After the translucent LOD pass: the pack's own target and blending back. */
    void endTranslucent();

    /**
     * The pack's id for a block state, from its {@code block.properties}
     * (Voxy's {@code customId}), 0 when the pack names none.
     */
    int customId(net.minecraft.world.level.block.state.BlockState state);

    /** Bumped when the pack's block ids may have changed (a pack or dimension change). */
    default int idsGeneration() {
        return generation();
    }

    /**
     * The game's depth texture under the pack (opaque terrain at the
     * translucent stage): translucent LODs give way where it is written.
     */
    int gameDepthTexture();

    /** After {@code glUseProgram} of an attached program: the pack's uniforms and samplers for this frame. */
    void bind(int program);

    /** Forgets an attached program before it is deleted. */
    void detach(int program);

    /**
     * After the opaque LOD passes: the pack's own target back; the
     * translucent depth starts from the opaque one.
     */
    void end();

    /** A program failed to build: the LODs stay off under this pack until it changes. */
    void fail(String reason);
}
