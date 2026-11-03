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

    /** Connects a linked program to the pack's uniforms and samplers. */
    void attach(int program, String name);

    /** After {@code glUseProgram} of an attached program: the pack's uniforms and samplers for this frame. */
    void bind(int program);

    /** Forgets an attached program before it is deleted. */
    void detach(int program);

    /** After the LOD passes: the pack's own target back. */
    void end();

    /** A program failed to build: the LODs stay off under this pack until it changes. */
    void fail(String reason);
}
