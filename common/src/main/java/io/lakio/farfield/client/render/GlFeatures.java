package io.lakio.farfield.client.render;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.opengl.GLCapabilities;

/**
 * What the LOD renderer needs from the OpenGL context: the functions it
 * calls, not a context version (ADR-166). The game asks for a 3.2 or 3.3
 * core context (NeoForge's early window has made 3.3 since FML 10, with no
 * way to ask more), and drivers give a forward-compatible context the newer
 * functions through their extensions; the shaders keep {@code #version 460}.
 */
public final class GlFeatures {

    /** The draw count comes from ARB_indirect_parameters: the context has no 4.6 entry point. */
    private static boolean indirectCountArb;
    /** Blending per draw buffer comes from ARB_draw_buffers_blend: no 4.0 entry point (FML 10's 3.3). */
    private static boolean blendArb;

    private GlFeatures() {
    }

    /** What is missing for the renderer, null when nothing is. */
    public static String missing(GLCapabilities c) {
        List<String> missing = new ArrayList<>();
        need(missing, "direct state access (4.5)", c.glCreateBuffers, c.glNamedBufferStorage,
                c.glNamedBufferSubData, c.glMapNamedBufferRange, c.glUnmapNamedBuffer, c.glClearNamedBufferData,
                c.glCopyNamedBufferSubData, c.glCreateTextures, c.glTextureStorage2D, c.glTextureSubImage2D,
                c.glTextureParameteri, c.glBindTextureUnit, c.glCreateVertexArrays, c.glVertexArrayElementBuffer,
                c.glNamedFramebufferTexture, c.glNamedFramebufferDrawBuffers, c.glCheckNamedFramebufferStatus);
        need(missing, "indirect draws (4.3)", c.glMultiDrawElementsIndirect);
        need(missing, "compute shaders (4.3)", c.glDispatchCompute, c.glMemoryBarrier);
        need(missing, "texture storage, copies and clears (4.2 to 4.4)", c.glTexStorage2D, c.glCopyImageSubData,
                c.glClearTexImage);
        need(missing, "blending per draw buffer (3.0)", c.glEnablei, c.glDisablei);
        if (c.glBlendFuncSeparatei == 0 && c.glBlendFuncSeparateiARB == 0) {
            missing.add("blend functions per draw buffer (4.0 or ARB_draw_buffers_blend)");
        }
        blendArb = c.glBlendFuncSeparatei == 0;
        need(missing, "timer queries (3.3)", c.glQueryCounter, c.glGetQueryObjectui64v);
        need(missing, "fences (3.2)", c.glFenceSync, c.glClientWaitSync);
        if (c.glMultiDrawElementsIndirectCount == 0 && c.glMultiDrawElementsIndirectCountARB == 0) {
            missing.add("indirect draw counts (4.6 or ARB_indirect_parameters)");
        }
        indirectCountArb = c.glMultiDrawElementsIndirectCount == 0;
        return missing.isEmpty() ? null : String.join(", ", missing);
    }

    /** True when the draw count comes from the ARB extension. */
    static boolean indirectCountFromExtension() {
        return indirectCountArb;
    }

    /** {@code glMultiDrawElementsIndirectCount}, from 4.6 or from ARB_indirect_parameters. */
    static void multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawCount, int maxDrawCount,
                                               int stride) {
        if (indirectCountArb) {
            ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(mode, type, indirect, drawCount, maxDrawCount,
                    stride);
        } else {
            GL46C.glMultiDrawElementsIndirectCount(mode, type, indirect, drawCount, maxDrawCount, stride);
        }
    }

    /** {@code glBlendFuncSeparatei}, from 4.0 or from ARB_draw_buffers_blend. */
    public static void blendFuncSeparatei(int buffer, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (blendArb) {
            org.lwjgl.opengl.ARBDrawBuffersBlend.glBlendFuncSeparateiARB(buffer, srcRgb, dstRgb, srcAlpha, dstAlpha);
        } else {
            org.lwjgl.opengl.GL40C.glBlendFuncSeparatei(buffer, srcRgb, dstRgb, srcAlpha, dstAlpha);
        }
    }

    private static void need(List<String> missing, String what, long... functions) {
        for (long f : functions) {
            if (f == 0) {
                missing.add(what);
                return;
            }
        }
    }
}
