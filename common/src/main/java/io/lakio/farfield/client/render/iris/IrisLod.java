package io.lakio.farfield.client.render.iris;

import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Matrix4f;

/**
 * The values the Voxy and DH contracts give every Iris program (ADR-113,
 * ADR-119): the LOD depth textures and matrices of the last frame, set by
 * {@link IrisLink}. Read by the Iris mixins. Render thread only.
 */
public final class IrisLod {

    static int opaqueDepth;
    static int translucentDepth;
    /** The LOD distance in chunks. */
    static int renderDistance;
    /**
     * Render targets a pack's Voxy contract names past Iris's 16 (Photon's
     * colortex16, its far water data), as textures of ours: index
     * {@link #FIRST_EXTRA} + i, 0 when none.
     */
    public static final int FIRST_EXTRA = 16;
    public static final int EXTRA_COUNT = 4;
    static final int[] extraTargets = new int[EXTRA_COUNT];

    public static int extraTarget(int index) {
        int i = index - FIRST_EXTRA;
        return i >= 0 && i < EXTRA_COUNT ? extraTargets[i] : 0;
    }
    static final Matrix4f proj = new Matrix4f();
    static final Matrix4f projInv = new Matrix4f();
    static final Matrix4f projPrev = new Matrix4f();
    static final Matrix4f modelView = new Matrix4f();
    static final Matrix4f modelViewInv = new Matrix4f();
    static final Matrix4f modelViewPrev = new Matrix4f();
    static final Matrix4f viewProj = new Matrix4f();
    static final Matrix4f viewProjInv = new Matrix4f();

    /** The pack's DH contract is followed (ADR-119): Iris's DH values are the LODs' own. */
    static boolean dh;
    static float near;
    static float far;
    static int distanceBlocks;

    private IrisLod() {
    }

    public static boolean dhActive() {
        return dh && opaqueDepth != 0;
    }

    public static float dhNear() {
        return near;
    }

    public static float dhFar() {
        return far;
    }

    public static int dhRenderDistanceBlocks() {
        return distanceBlocks;
    }

    /** A copy of the LODs' projection, as Iris hands its DH projection out. */
    public static Matrix4f dhProjection() {
        return new Matrix4f(proj);
    }

    public static int opaqueDepthTexture() {
        return opaqueDepth;
    }

    public static int translucentDepthTexture() {
        return translucentDepth != 0 ? translucentDepth : opaqueDepth;
    }

    /** Called for each Iris program's uniforms ({@code CommonUniformsMixin}). */
    public static void addUniforms(UniformHolder uniforms) {
        UniformUpdateFrequency f = UniformUpdateFrequency.PER_FRAME;
        uniforms.uniformMatrix(f, "vxProj", () -> proj)
                .uniformMatrix(f, "vxProjInv", () -> projInv)
                .uniformMatrix(f, "vxProjPrev", () -> projPrev)
                .uniformMatrix(f, "vxModelView", () -> modelView)
                .uniformMatrix(f, "vxModelViewInv", () -> modelViewInv)
                .uniformMatrix(f, "vxModelViewPrev", () -> modelViewPrev)
                .uniformMatrix(f, "vxViewProj", () -> viewProj)
                .uniformMatrix(f, "vxViewProjInv", () -> viewProjInv)
                .uniform1i(f, "vxRenderDistance", () -> renderDistance);
    }

    /** The matrices of a new frame: the previous ones kept for the packs' reprojection. */
    static void setFrame(Matrix4f projection, Matrix4f view, int distanceChunks, float nearPlane, float farPlane) {
        near = nearPlane;
        far = farPlane;
        distanceBlocks = distanceChunks << 4;
        projPrev.set(proj);
        modelViewPrev.set(modelView);
        proj.set(projection);
        proj.invert(projInv);
        modelView.set(view);
        modelView.invert(modelViewInv);
        proj.mul(modelView, viewProj);
        viewProj.invert(viewProjInv);
        renderDistance = distanceChunks;
    }
}
