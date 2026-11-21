package io.lakio.farfield.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.api.internal.ClientApiState;
import io.lakio.farfield.compat.GlCompat;
import io.lakio.farfield.core.render.LodDepthMath;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.util.Set;

import static org.lwjgl.opengl.GL46C.*;

/**
 * The LOD depth kept for other mods (ADR-173, {@code FarfieldClientApi}):
 * after the opaque LODs drew into the main target and before its depth is
 * cleared for the game's terrain, a copy of that depth and the matrix that
 * reads it. Nothing is copied until a mod asked for it.
 */
final class LodDepthShare {

    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] inverse = new float[16];

    private int texture;
    private int width;
    private int height;
    private int format;
    private boolean failed;
    private boolean checked;
    private int loggedConsumers;
    /** The view distance last logged: logged again once it moved by a quarter (ADR-174). */
    private double loggedExtent;

    /** At the start of the level's frame: nothing to read until the LODs drew. */
    void beginFrame() {
        ClientApiState.clear();
    }

    /**
     * After the opaque LODs drew into the main target, before its depth is
     * cleared. The depth there is the LODs' own (near and far below).
     */
    void capture(Matrix4f projectionMatrix, Matrix4f viewRotation, float near, float far, double extent) {
        Set<String> consumers = ClientApiState.consumers();
        if (consumers.isEmpty() || failed || extent <= 0) {
            return; // extent 0: no LOD drawn yet, the frame stays inactive
        }
        if (consumers.size() != loggedConsumers) {
            loggedConsumers = consumers.size();
            Farfield.LOG.info("LOD depth shared with {} (Farfield client API)", String.join(", ", consumers));
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int source = GlCompat.mainDepthTexture();
        if (!ensureTexture(source, main.width, main.height)) {
            return;
        }
        boolean first = !checked;
        if (first) {
            drainErrors();
        }
        glCopyImageSubData(source, GL_TEXTURE_2D, 0, 0, 0, 0, texture, GL_TEXTURE_2D, 0, 0, 0, 0,
                width, height, 1);
        if (first) {
            int error = glGetError();
            checked = true;
            if (error != GL_NO_ERROR) {
                fail("copying the depth failed (GL error 0x" + Integer.toHexString(error) + ")");
                return;
            }
            Farfield.LOG.info("LOD depth shared: {}x{}, depth format 0x{}", width, height, Integer.toHexString(format));
        }
        projectionMatrix.get(projection);
        viewRotation.get(view);
        if (!LodDepthMath.inverseViewProjection(projection, view, near, far, inverse)) {
            return;
        }
        // The LODs draw with OpenGL's default clip range and depth 1 far, on every version (ADR-171).
        ClientApiState.publish(texture, false, false, inverse, (float) extent);
        if (Math.abs(extent - loggedExtent) > loggedExtent / 4) {
            loggedExtent = extent;
            Farfield.LOG.info("LOD depth shared: view distance {} blocks", Math.round(extent));
        }
    }

    private boolean ensureTexture(int source, int w, int h) {
        if (texture != 0 && w == width && h == height) {
            return true;
        }
        GLCapabilities caps = GL.getCapabilities();
        if (caps.glCopyImageSubData == 0L || caps.glGetTextureLevelParameteriv == 0L) {
            fail("the driver has no image copy or texture query");
            return false;
        }
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
        format = sizedDepthFormat(source);
        if (format == 0) {
            fail("the game's depth format is unknown");
            return false;
        }
        texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, 1, format, w, h);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTextureParameteri(texture, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        width = w;
        height = h;
        checked = false;
        return true;
    }

    /** The game's depth texture's sized format: the copy needs the same one. */
    private static int sizedDepthFormat(int source) {
        int internal = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_INTERNAL_FORMAT);
        switch (internal) {
            case GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32, GL_DEPTH_COMPONENT32F,
                 GL_DEPTH24_STENCIL8, GL_DEPTH32F_STENCIL8 -> {
                return internal;
            }
            default -> {
            }
        }
        // An unsized format: the driver's choice, read from its sizes.
        int depthBits = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_DEPTH_SIZE);
        int stencilBits = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_STENCIL_SIZE);
        boolean floating = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_DEPTH_TYPE) == GL_FLOAT;
        if (stencilBits > 0) {
            return floating ? GL_DEPTH32F_STENCIL8 : GL_DEPTH24_STENCIL8;
        }
        if (floating) {
            return GL_DEPTH_COMPONENT32F;
        }
        return switch (depthBits) {
            case 16 -> GL_DEPTH_COMPONENT16;
            case 24 -> GL_DEPTH_COMPONENT24;
            case 32 -> GL_DEPTH_COMPONENT32;
            default -> 0;
        };
    }

    private void fail(String why) {
        failed = true;
        ClientApiState.clear();
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
        Farfield.LOG.warn("LOD depth not shared: {}", why);
    }

    private static void drainErrors() {
        for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; i++) {
            // others' errors, not ours
        }
    }
}
