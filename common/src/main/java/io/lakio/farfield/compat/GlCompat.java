package io.lakio.farfield.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
/*import io.lakio.farfield.mixin.client.GlDeviceAccessor;
import io.lakio.farfield.mixin.client.GpuDeviceAccessor;
*///?} else if >=1.21.11 {
/*import com.mojang.blaze3d.opengl.GlDevice;
*///?}
//? if >=1.21.11 {
/*import com.mojang.blaze3d.opengl.DirectStateAccess;
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

    *///?}
    //? if >=26.1 {
    /*// Since 26.1 the GPU device is a front over a backend, and the OpenGL backend's classes
    // are package-private: their fields are read through accessor mixins (ADR-171).
    private static com.mojang.blaze3d.systems.GpuDeviceBackend glDevice() {
        return (com.mojang.blaze3d.systems.GpuDeviceBackend) real(((GpuDeviceAccessor) RenderSystem.getDevice()).farfield$backend());
    }

    private static DirectStateAccess directStateAccess() {
        return ((GlDeviceAccessor) glDevice()).farfield$directStateAccess();
    }

    // The OpenGL backend's one encoder.
    private static GlCommandEncoderAccessor encoder() {
        return (GlCommandEncoderAccessor) real(glDevice().createCommandEncoder());
    }
    *///?} else if >=1.21.11 {
    /*// The game's OpenGL device.
    private static GlDevice glDevice() {
        return (GlDevice) real(RenderSystem.getDevice());
    }

    private static DirectStateAccess directStateAccess() {
        return glDevice().directStateAccess();
    }

    private static GlCommandEncoderAccessor encoder() {
        return (GlCommandEncoderAccessor) glDevice().createCommandEncoder();
    }
    *///?}

    /** The game's graphics backend when it is not OpenGL (26.3's Vulkan), else null (ADR-171). */
    public static String backendName() {
        //? if >=26.3 {
        /*Object backend = real(((GpuDeviceAccessor) RenderSystem.getDevice()).farfield$backend());
        String name = backend.getClass().getSimpleName();
        return name.equals("GlDevice") ? null : name;
        *///?} else {
        return null;
        //?}
    }

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
        //? if >=26.1 {
        /*return glTexture(mc.gameRenderer.levelLightmap().texture()).glId();
        *///?} else if >=1.21.11 {
        /*return glTexture(mc.gameRenderer.lightTexture().getTextureView().texture()).glId();
        *///?} else {
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        return RenderSystem.getShaderTexture(2);
        //?}
    }

    //? if >=26.2 {
    /*// Since 26.2 the device keeps its framebuffers in a cache of its package-private backend:
    // the raw draws make their own, one per pair of target textures (ADR-171), dropped once the
    // game closes a texture (a resize makes new ones).
    private record Framebuffer(com.mojang.blaze3d.textures.GpuTexture color,
                               com.mojang.blaze3d.textures.GpuTexture depth, int id) {
    }

    private static final java.util.List<Framebuffer> FRAMEBUFFERS = new java.util.ArrayList<>();

    private static int framebuffer(com.mojang.blaze3d.textures.GpuTexture color,
                                   com.mojang.blaze3d.textures.GpuTexture depth) {
        int found = 0;
        for (java.util.Iterator<Framebuffer> it = FRAMEBUFFERS.iterator(); it.hasNext(); ) {
            Framebuffer f = it.next();
            if (f.color().isClosed() || (f.depth() != null && f.depth().isClosed())) {
                GlStateManager._glDeleteFramebuffers(f.id());
                it.remove();
            } else if (f.color() == color && f.depth() == depth) {
                found = f.id();
            }
        }
        if (found != 0) {
            return found;
        }
        int previous = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        int id = GL30C.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, id);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D,
                glTexture(color).glId(), 0);
        if (depth != null) {
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D,
                    glTexture(depth).glId(), 0);
        }
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, previous);
        FRAMEBUFFERS.add(new Framebuffer(color, depth, id));
        return id;
    }
    *///?} else if >=1.21.11 {
    /*private static int framebuffer(com.mojang.blaze3d.textures.GpuTexture color,
                                   com.mojang.blaze3d.textures.GpuTexture depth) {
        return glTexture(color).getFbo(directStateAccess(), glTexture(depth));
    }
    *///?}

    /** The OpenGL framebuffer of the game's main render target (its color and depth). */
    public static int mainFramebuffer() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        //? if >=1.21.11 {
        /*return framebuffer(main.getColorTexture(), main.getDepthTexture());
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
        /*GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER,
                framebuffer(target.getColorTexture(), target.getDepthTexture()));
        GlStateManager._viewport(0, 0, target.width, target.height);
        *///?}
    }

    /**
     * The depth of a pixel nothing drew on, as the game clears it: 1 (far) up
     * to 26.1, 0 since 26.2, which draws with reversed depth (clip control
     * zero to one, depth tests greater or equal, ADR-171).
     */
    public static double gameClearDepth() {
        //? if >=26.2 {
        /*return 0.0;
        *///?} else {
        return 1.0;
        //?}
    }

    //? if >=26.2 {
    /*private static int savedClipOrigin;
    private static int savedClipDepthMode;
    private static boolean clipSaved;
    *///?}

    /**
     * Before the LODs draw: their depth is a standard one over their own range
     * (lod.vert), tested less or equal against a depth cleared to 1. Since 26.2
     * the game draws with reversed depth, so the LODs put back the clip control
     * of 26.1 for their draws and, when they draw into the game's target
     * ({@code clearDepth}), clear its depth to their far value first.
     */
    public static void beginLodDepth(boolean clearDepth) {
        //? if >=26.2 {
        /*org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
        clipSaved = caps.glClipControl != 0L;
        if (clipSaved) {
            savedClipOrigin = GL11C.glGetInteger(org.lwjgl.opengl.GL45C.GL_CLIP_ORIGIN);
            savedClipDepthMode = GL11C.glGetInteger(org.lwjgl.opengl.GL45C.GL_CLIP_DEPTH_MODE);
            org.lwjgl.opengl.GL45C.glClipControl(org.lwjgl.opengl.GL45C.GL_LOWER_LEFT,
                    org.lwjgl.opengl.GL45C.GL_NEGATIVE_ONE_TO_ONE);
        }
        if (clearDepth) {
            GlStateManager._disableScissorTest();
            GlStateManager._depthMask(true);
            GL11C.glClearDepth(1.0);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
        }
        *///?}
    }

    /** After the LODs drew: the game's own clip control back. */
    public static void endLodDepth() {
        //? if >=26.2 {
        /*if (clipSaved) {
            org.lwjgl.opengl.GL45C.glClipControl(savedClipOrigin, savedClipDepthMode);
            clipSaved = false;
        }
        *///?}
    }

    /** Clears the main target's depth (the LODs drew with their own depth range) to the game's clear depth. */
    public static void clearMainDepth() {
        //? if >=1.21.11 {
        /*RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(
                Minecraft.getInstance().getMainRenderTarget().getDepthTexture(), gameClearDepth());
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
        /*encoder().farfield$setLastProgram(null);
        *///?} else {
        BufferUploader.invalidate();
        //?}
        // 26.2's encoder also keeps the vertex array it set up last; 26.3's sets it up at every draw (ADR-171).
        //? if >=26.2 && <26.3 {
        /*encoder().farfield$setLastVertexArray(null);
        *///?}
    }
}
