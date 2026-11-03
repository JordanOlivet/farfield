package io.lakio.farfield.client.render.iris;

import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Matrix4f;

/**
 * The values the Voxy contract gives every Iris program (ADR-113): the LOD
 * depth textures and matrices of the last frame, set by {@link IrisLink}.
 * Read by the Iris mixins. Render thread only.
 */
public final class IrisLod {

    static int opaqueDepth;
    static int translucentDepth;
    /** The LOD distance in chunks. */
    static int renderDistance;
    static final Matrix4f proj = new Matrix4f();
    static final Matrix4f projInv = new Matrix4f();
    static final Matrix4f projPrev = new Matrix4f();
    static final Matrix4f modelView = new Matrix4f();
    static final Matrix4f modelViewInv = new Matrix4f();
    static final Matrix4f modelViewPrev = new Matrix4f();
    static final Matrix4f viewProj = new Matrix4f();
    static final Matrix4f viewProjInv = new Matrix4f();

    private IrisLod() {
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
    static void setFrame(Matrix4f projection, Matrix4f view, int distanceChunks) {
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
