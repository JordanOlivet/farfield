package io.lakio.farfield.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
//? if >=1.21.11 {
/*import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import io.lakio.farfield.mixin.client.GlCommandEncoderAccessor;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
*///?} else {
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import org.lwjgl.opengl.GL11C;
//?}

/**
 * What the LOD renderer's own OpenGL calls need from the game, across
 * Minecraft versions (ADR-165). Up to 1.21.4 the game drew through
 * {@code RenderSystem}'s global state; since 1.21.5 it draws through render
 * passes of its GPU device, which keep their own caches: a raw draw between
 * them binds the main target itself and tells the device's encoder its
 * program changed.
 */
public final class GlCompat {
    private GlCompat() {
    }

    //? if >=1.21.11 {
    /*// NeoForge's GPU validation (on in its dev runs) wraps the device, textures and views; their
    // getRealDevice / getRealTexture / getRealTextureView give the OpenGL objects underneath.
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> REAL =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String VALIDATION = "net.neoforged.neoforge.client.blaze3d.validation.";

    private static Object real(Object o) {
        while (o != null && o.getClass().getName().startsWith(VALIDATION)) {
            java.lang.reflect.Method getter = REAL.computeIfAbsent(o.getClass(), c -> {
                for (java.lang.reflect.Method m : c.getMethods()) {
                    if (m.getName().startsWith("getReal") && m.getParameterCount() == 0) {
                        return m;
                    }
                }
                throw new IllegalStateException("no getReal* on " + c.getName());
            });
            try {
                o = getter.invoke(o);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("unwrapping " + o.getClass().getName(), e);
            }
        }
        return o;
    }

    // The OpenGL texture under a game texture.
    public static GlTexture glTexture(com.mojang.blaze3d.textures.GpuTexture texture) {
        return texture == null ? null : (GlTexture) real(texture);
    }

    // The game's OpenGL device.
    public static GlDevice glDevice() {
        return (GlDevice) real(RenderSystem.getDevice());
    }
    *///?}

    /**
     * How long a section the game just built takes to fade in, in
     * milliseconds: vanilla's Chunk Fade option since 1.21.9 (Sodium 0.8
     * follows it), 0 before.
     */
    public static long chunkFadeMillis(Minecraft mc) {
        //? if >=1.21.11 {
        /*return Math.round(mc.options.chunkSectionFadeInTime().get() * 1000.0);
        *///?} else {
        return 0;
        //?}
    }

    /** The active texture unit, {@code GL_TEXTURE0 + n}. */
    public static int activeTexture() {
        //? if >=1.21.11 {
        /*return GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        *///?} else {
        return GlStateManager._getActiveTexture();
        //?}
    }

    /** The OpenGL name of vanilla's lightmap, as the game renderer updated it for this frame. */
    public static int lightmapTexture(Minecraft mc) {
        //? if >=1.21.11 {
        /*return glTexture(mc.gameRenderer.lightTexture().getTextureView().texture()).glId();
        *///?} else {
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        return RenderSystem.getShaderTexture(2);
        //?}
    }

    /** The OpenGL framebuffer of the game's main render target (its color and depth). */
    public static int mainFramebuffer() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        //? if >=1.21.11 {
        /*return glTexture(main.getColorTexture()).getFbo(glDevice().directStateAccess(),
                glTexture(main.getDepthTexture()));
        *///?} else {
        return main.frameBufferId;
        //?}
    }

    /** The OpenGL name of the main target's depth texture. */
    public static int mainDepthTexture() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        //? if >=1.21.11 {
        /*return glTexture(main.getDepthTexture()).glId();
        *///?} else {
        return main.getDepthTextureId();
        //?}
    }

    /**
     * Before a raw draw into a target: since 1.21.5 the framebuffer
     * bound between the game's render passes is whichever the last one used.
     */
    public static void bindTarget(RenderTarget target) {
        //? if >=1.21.11 {
        /*GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, glTexture(target.getColorTexture()).getFbo(
                glDevice().directStateAccess(), glTexture(target.getDepthTexture())));
        GlStateManager._viewport(0, 0, target.width, target.height);
        *///?}
    }

    /** Clears the main target's depth (the LODs drew with their own depth range). */
    public static void clearMainDepth() {
        //? if >=1.21.11 {
        /*RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(
                Minecraft.getInstance().getMainRenderTarget().getDepthTexture(), 1.0);
        *///?} else {
        RenderSystem.clear(GL11C.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        //?}
    }

    /**
     * After a raw draw: the game's caches of what it last bound are stale
     * (1.21.1's vertex buffer, the encoder's program since 1.21.5).
     */
    public static void endRawDraw() {
        //? if >=1.21.11 {
        /*((GlCommandEncoderAccessor) glDevice().createCommandEncoder()).farfield$setLastProgram(null);
        *///?} else {
        BufferUploader.invalidate();
        //?}
    }
}
