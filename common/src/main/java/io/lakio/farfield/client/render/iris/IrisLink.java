package io.lakio.farfield.client.render.iris;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.render.ShaderPackLink;
import io.lakio.farfield.core.shader.DhPatch;
import io.lakio.farfield.mixin.iris.IrisRenderingPipelineAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.LocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.targets.DepthTexture;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform3f;
import static org.lwjgl.opengl.GL20C.glUniformMatrix3fv;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL44C.glClearTexImage;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_DST_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_DST_COLOR;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_DST_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_DST_COLOR;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_COLOR;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA_SATURATE;
import static org.lwjgl.opengl.GL11C.GL_SRC_COLOR;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_ZERO;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL11C.GL_NONE;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_RGBA16;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL45C.glCreateTextures;
import static org.lwjgl.opengl.GL45C.glTextureParameteri;
import static org.lwjgl.opengl.GL45C.glTextureStorage2D;
import static org.lwjgl.opengl.GL45C.glNamedFramebufferDrawBuffers;
import static org.lwjgl.opengl.GL30C.glDisablei;
import static org.lwjgl.opengl.GL30C.glEnablei;
import static org.lwjgl.opengl.GL40C.glBlendFuncSeparatei;
import static org.lwjgl.opengl.GL43C.glCopyImageSubData;
import static org.lwjgl.opengl.GL45C.glCheckNamedFramebufferStatus;
import static org.lwjgl.opengl.GL45C.glNamedFramebufferTexture;

/**
 * The LOD passes through a shader pack's Voxy contract, over Iris
 * (ADR-113): the pack's opaque snippet linked into {@code lod.frag}, its
 * draw buffers, a depth texture of the LODs' own that the pack's composites
 * read as {@code vxDepthTexOpaque}. Or through its Distant Horizons
 * contract (ADR-119): its {@code dh_terrain} and {@code dh_water} programs
 * as Iris transforms them, run on the LOD quads by a bridge of ours, the
 * same depth textures read as {@code dhDepthTex}. Render thread only.
 */
public final class IrisLink implements ShaderPackLink {

    private final Set<Integer> reservedUnits;
    private WorldRenderingPipeline pipeline;
    private VoxyContract contract;
    /** Why the LODs are off under the current pack, or null. */
    private String off;
    private int generation;
    private DepthTexture depth;
    /** The LODs' nearest depth, translucent faces included: the opaque depth, then the translucent pass's. */
    private DepthTexture translucentDepth;
    private GlFramebuffer framebuffer;
    private GlFramebuffer translucentFramebuffer;
    /** Our textures for the render targets past Iris's that the contract names, by index - 16; 0 when none. */
    private final int[] extraTargets = new int[IrisLod.EXTRA_COUNT];
    private RenderTargets framebufferTargets;
    private int width;
    private int height;
    private int previousFramebuffer;
    private final int[] previousViewport = new int[4];
    private static final float[] ONE = {1f};
    private final Matrix4f lodProjection = new Matrix4f();
    private final Matrix4f lodView = new Matrix4f();
    private final Int2ObjectOpenHashMap<Bound> bound = new Int2ObjectOpenHashMap<>();
    /** The pack's DH programs when its DH contract is followed (ADR-119), else null. */
    private DhProgram dhTerrain;
    private DhProgram dhWater;

    /** A program's Iris uniforms, samplers and images, and the DH program uniforms Iris sets itself (null outside DH). */
    private record Bound(ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images, Object pass,
                         int[] dhUniforms) {
    }

    /** A DH program of the pack, transformed by Iris and patched for the bridge. */
    private record DhProgram(String name, String vertex, String fragment, int[] drawBuffers, BlendModeOverride blend,
                             java.util.List<BufferBlendInformation> bufferBlends) {
    }

    /** Uniforms Iris's IrisLodRenderProgram sets on a DH program, in {@link Bound#dhUniforms} order. */
    private static final String[] DH_UNIFORMS = {"iris_ProjectionMatrix", "iris_ProjectionMatrixInverse",
            "iris_ModelViewMatrix", "iris_ModelViewMatrixInverse", "iris_NormalMatrix", "modelOffset", "mircoOffset",
            "worldYOffset", "clipDistance"};

    /**
     * @param ownUnits the texture units the LOD program binds itself (atlas,
     *                 lightmap), kept out of the pack's samplers
     */
    public IrisLink(int... ownUnits) {
        Set<Integer> units = new HashSet<>(IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
        for (int u : ownUnits) {
            units.add(u);
        }
        reservedUnits = Set.copyOf(units);
    }

    /** The mode {@link #frame} found for this frame. */
    private int mode = VANILLA;

    @Override
    public void frame(Matrix4f projection, Matrix4f viewRotation, float near, float far, int distance) {
        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        if (!(current instanceof IrisRenderingPipeline iris)) {
            if (pipeline != null) {
                release();
                pipeline = null;
            }
            mode = VANILLA;
            return;
        }
        if (current != pipeline) {
            release();
            pipeline = current;
            load();
        }
        // The pack reads the LOD depth and matrices under VOXY whether the LODs
        // draw or not: an empty depth and the real matrices then (no LODs).
        RenderTargets targets = ((IrisRenderingPipelineAccessor) iris).farfield$renderTargets();
        int w = targets.getCurrentWidth();
        int h = targets.getCurrentHeight();
        if (depth == null || w != width || h != height) {
            releaseTargets();
            depth = new DepthTexture("Farfield LOD depth", w, h, DepthBufferFormat.DEPTH32F);
            translucentDepth = new DepthTexture("Farfield LOD translucent depth", w, h, DepthBufferFormat.DEPTH32F);
            width = w;
            height = h;
        }
        glClearTexImage(depth.getTextureId(), 0, GL_DEPTH_COMPONENT, GL_FLOAT, ONE);
        glClearTexImage(translucentDepth.getTextureId(), 0, GL_DEPTH_COMPONENT, GL_FLOAT, ONE);
        for (int i = 0; i < extraTargets.length; i++) {
            if (extraTargets[i] != 0) {
                glClearTexImage(extraTargets[i], 0, GL_RGBA, GL_UNSIGNED_SHORT, (java.nio.ByteBuffer) null);
            }
            IrisLod.extraTargets[i] = extraTargets[i];
        }
        // The game's projection with the LODs' depth range, as lod.vert computes it.
        lodProjection.set(projection);
        lodProjection.m22(-(far + near) / (far - near));
        lodProjection.m32(-2f * far * near / (far - near));
        IrisLod.setFrame(lodProjection, viewRotation, distance >> 4, near, far);
        lodView.set(viewRotation);
        IrisLod.opaqueDepth = depth.getTextureId();
        IrisLod.translucentDepth = translucentDepth.getTextureId();
        if (off != null) {
            mode = NONE;
            return;
        }
        if (framebuffer == null || targets != framebufferTargets) {
            if (framebuffer != null) {
                framebuffer.destroy();
            }
            destroy(translucentFramebuffer);
            translucentFramebuffer = null;
            int[] opaqueBuffers;
            if (dhTerrain != null) {
                // As Iris's DH programs: the water after the pack's deferred passes, on the buffers as after translucent.
                opaqueBuffers = dhTerrain.drawBuffers;
                framebuffer = target(targets, iris.getFlippedAfterPrepare(), opaqueBuffers, depth);
                translucentFramebuffer = target(targets, iris.getFlippedAfterTranslucent(), dhWater.drawBuffers,
                        translucentDepth);
            } else {
                opaqueBuffers = contract.opaqueDrawBuffers;
                createExtraTargets(targets.getRenderTargetCount(), w, h);
                framebuffer = target(targets, iris.getFlippedAfterPrepare(), opaqueBuffers, depth);
                // Translucent LODs draw before the pack's deferred passes, as Voxy's: the buffers as after prepare.
                translucentFramebuffer = target(targets, iris.getFlippedAfterPrepare(), contract.translucentDrawBuffers,
                        translucentDepth);
            }
            framebufferTargets = targets;
            if (framebuffer == null) {
                fail("its opaque draw buffers " + java.util.Arrays.toString(opaqueBuffers)
                        + " name no render target of Iris");
                mode = NONE;
                return;
            }
            Farfield.LOG.info("Shader pack LODs: targets {}x{} built, status {} and {}", w, h,
                    Integer.toHexString(glCheckNamedFramebufferStatus(framebuffer.getId(), GL_DRAW_FRAMEBUFFER)),
                    translucentFramebuffer == null ? "none"
                            : Integer.toHexString(glCheckNamedFramebufferStatus(translucentFramebuffer.getId(), GL_DRAW_FRAMEBUFFER)));
        }
        mode = dhTerrain != null ? DH : VOXY;
    }

    @Override
    public int mode() {
        return mode;
    }

    @Override
    public int begin() {
        if (mode != VOXY && mode != DH) {
            return mode;
        }
        bindTarget(framebuffer);
        return mode;
    }

    /**
     * A gbuffer framebuffer of the pack's draw buffers over a LOD depth of
     * ours, or null when none of them exists under Iris. A buffer past
     * Iris's render targets (Photon lists colortex16, Iris has 0 to 15)
     * takes no attachment: its location's output goes nowhere.
     */
    private GlFramebuffer target(RenderTargets targets, ImmutableSet<Integer> flipped, int[] drawBuffers,
                                 DepthTexture depth) {
        int count = targets.getRenderTargetCount();
        int[] valid = java.util.Arrays.stream(drawBuffers).filter(b -> b >= 0 && b < count).toArray();
        if (valid.length == 0) {
            return null;
        }
        GlFramebuffer fb = targets.createGbufferFramebuffer(flipped, valid);
        // Iris attached the game's depth, a depth-stencil texture when a mod
        // enables the stencil: its stencil half stayed attached beside our
        // depth, and every LOD draw failed (GL_INVALID_FRAMEBUFFER_OPERATION).
        glNamedFramebufferTexture(fb.getId(), GL_DEPTH_STENCIL_ATTACHMENT, 0, 0);
        fb.addDepthAttachment(depth.getTextureId());
        if (valid.length != drawBuffers.length) {
            // Each location keeps its place: an attachment in order, or none.
            // A target past Iris's goes to our own texture of that index, after Iris's attachments.
            int[] buffers = new int[drawBuffers.length];
            int attachment = 0;
            int extra = valid.length;
            for (int i = 0; i < drawBuffers.length; i++) {
                int b = drawBuffers[i];
                if (b >= 0 && b < count) {
                    buffers[i] = GL_COLOR_ATTACHMENT0 + attachment++;
                } else if (IrisLod.extraTarget(b) != 0 || extraTexture(b) != 0) {
                    glNamedFramebufferTexture(fb.getId(), GL_COLOR_ATTACHMENT0 + extra, extraTexture(b), 0);
                    buffers[i] = GL_COLOR_ATTACHMENT0 + extra++;
                } else {
                    buffers[i] = GL_NONE;
                }
            }
            glNamedFramebufferDrawBuffers(fb.getId(), buffers);
            Farfield.LOG.info("Shader pack LODs: draw buffers {} past Iris's {} render targets go to textures of ours",
                    java.util.Arrays.toString(drawBuffers), count);
        }
        return fb;
    }

    private int extraTexture(int index) {
        int i = index - IrisLod.FIRST_EXTRA;
        return i >= 0 && i < extraTargets.length ? extraTargets[i] : 0;
    }

    /**
     * A texture of ours for each render target the contract names past
     * Iris's (RGBA16 as Photon declares its colortex16), cleared every frame.
     */
    private void createExtraTargets(int count, int w, int h) {
        for (int[] list : new int[][]{contract.opaqueDrawBuffers, contract.translucentDrawBuffers}) {
            for (int b : list) {
                int i = b - IrisLod.FIRST_EXTRA;
                if (b >= count && i >= 0 && i < extraTargets.length && extraTargets[i] == 0) {
                    int t = glCreateTextures(GL_TEXTURE_2D);
                    glTextureStorage2D(t, 1, GL_RGBA16, w, h);
                    glTextureParameteri(t, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                    glTextureParameteri(t, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                    glTextureParameteri(t, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTextureParameteri(t, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    glClearTexImage(t, 0, GL_RGBA, GL_UNSIGNED_SHORT, (java.nio.ByteBuffer) null);
                    extraTargets[i] = t;
                    IrisLod.extraTargets[i] = t;
                }
            }
        }
    }

    /** Destroys a framebuffer that Iris may have destroyed already, with its render targets. */
    private static void destroy(GlFramebuffer fb) {
        if (fb == null) {
            return;
        }
        try {
            fb.destroy();
        } catch (RuntimeException e) {
            // gone with the pack's targets
        }
    }

    @Override
    public void end() {
        restoreTarget();
        // Translucent LODs test against the opaque ones, and the pack reads
        // the nearest of both: start from the opaque depth.
        glCopyImageSubData(depth.getTextureId(), GL_TEXTURE_2D, 0, 0, 0, 0,
                translucentDepth.getTextureId(), GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
    }

    private void bindTarget(GlFramebuffer fb) {
        // A program Iris disables (the vanilla sky under most packs) locks the depth
        // and color masks and the blending, then ignores every change until
        // the next program: the LOD passes wrote nothing. Their state is theirs.
        if (DepthColorStorage.isDepthColorLocked()) {
            DepthColorStorage.unlockDepthColor();
        }
        if (BlendModeStorage.isBlendLocked()) {
            BlendModeStorage.restoreBlend();
        }
        previousFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, previousViewport);
        fb.bind();
        // Under TAAU the pack draws its terrain into the scaled part of its buffers.
        float sx = contract != null ? contract.renderScale[0] : 1f;
        float sy = contract != null ? contract.renderScale[1] : 1f;
        glViewport(0, 0, Math.max(1, Math.round(width * sx)), Math.max(1, Math.round(height * sy)));
    }

    private void restoreTarget() {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
    }

    @Override
    public boolean beginTranslucent() {
        if (mode == DH && translucentFramebuffer != null) {
            bindTarget(translucentFramebuffer);
            dhBlend(dhWater);
            return true;
        }
        if (mode != VOXY || contract.translucent == null || translucentFramebuffer == null) {
            return false;
        }
        bindTarget(translucentFramebuffer);
        // The pack's blending per draw buffer: -1 for every buffer, then each
        // location's own; without the key, the usual alpha blending.
        int buffers = contract.translucentDrawBuffers.length;
        String all = contract.blending.getOrDefault(-1,
                contract.blending.isEmpty() ? "SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA" : "off");
        for (int i = 0; i < buffers; i++) {
            blend(i, contract.blending.getOrDefault(i, all));
        }
        return true;
    }

    /**
     * The blending of a DH program, as Iris's IrisLodRenderProgram binds it:
     * the pack's override and per-buffer ones, else the usual alpha blending.
     */
    private void dhBlend(DhProgram program) {
        if (program.blend != null) {
            program.blend.apply();
            dhBlendApplied = true;
        } else {
            for (int i = 0; i < program.drawBuffers.length; i++) {
                blend(i, "SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA");
            }
        }
        for (BufferBlendInformation info : program.bufferBlends) {
            int i = indexOf(program.drawBuffers, info.index());
            if (i < 0) {
                continue;
            }
            if (info.blendMode() == null) {
                glDisablei(GL_BLEND, i);
            } else {
                glEnablei(GL_BLEND, i);
                glBlendFuncSeparatei(i, info.blendMode().srcRgb(), info.blendMode().dstRgb(),
                        info.blendMode().srcAlpha(), info.blendMode().dstAlpha());
            }
        }
    }

    private boolean dhBlendApplied;

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** One draw buffer's blending: "off", or four GL factor names. */
    private static void blend(int buffer, String mode) {
        String[] f = mode.trim().split("\\s+");
        if (f.length != 4) {
            glDisablei(GL_BLEND, buffer);
            return;
        }
        glEnablei(GL_BLEND, buffer);
        glBlendFuncSeparatei(buffer, factor(f[0]), factor(f[1]), factor(f[2]), factor(f[3]));
    }

    private static int factor(String name) {
        return switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "ZERO" -> GL_ZERO;
            case "ONE" -> GL_ONE;
            case "SRC_COLOR" -> GL_SRC_COLOR;
            case "ONE_MINUS_SRC_COLOR" -> GL_ONE_MINUS_SRC_COLOR;
            case "DST_COLOR" -> GL_DST_COLOR;
            case "ONE_MINUS_DST_COLOR" -> GL_ONE_MINUS_DST_COLOR;
            case "SRC_ALPHA" -> GL_SRC_ALPHA;
            case "ONE_MINUS_SRC_ALPHA" -> GL_ONE_MINUS_SRC_ALPHA;
            case "DST_ALPHA" -> GL_DST_ALPHA;
            case "ONE_MINUS_DST_ALPHA" -> GL_ONE_MINUS_DST_ALPHA;
            case "SRC_ALPHA_SATURATE" -> GL_SRC_ALPHA_SATURATE;
            default -> GL_ONE;
        };
    }

    @Override
    public void endTranslucent() {
        restoreTarget();
        if (dhBlendApplied) {
            BlendModeOverride.restore();
            dhBlendApplied = false;
        }
        // The indexed calls went around the game's state cache: every buffer
        // back off with the default factors, then the cache set through calls
        // that differ from what it holds, so they reach GL.
        glDisable(GL_BLEND);
        RenderSystem.enableBlend();
        RenderSystem.disableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ZERO,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ZERO);
        RenderSystem.defaultBlendFunc();
    }

    @Override
    public int customId(net.minecraft.world.level.block.state.BlockState state) {
        if (dhTerrain != null) {
            return DhMaterials.of(state); // DH's material class, the pack's dhMaterialId
        }
        // The map Iris built from the pack's block.properties for its own terrain.
        it.unimi.dsi.fastutil.objects.Object2IntMap<net.minecraft.world.level.block.state.BlockState> ids =
                net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getBlockStateIds();
        if (ids == null || state == null) {
            return 0;
        }
        return Math.max(0, ids.getOrDefault(state, 0));
    }

    @Override
    public int gameDepthTexture() {
        return ((IrisRenderingPipelineAccessor) pipeline).farfield$renderTargets().getDepthTexture();
    }

    @Override
    public int generation() {
        return generation;
    }

    @Override
    public String opaqueUnit(String parameters) {
        return contract == null ? null : unit(contract.opaque, "VOXY_OPAQUE_PASS", parameters);
    }

    @Override
    public String translucentUnit(String parameters) {
        return contract == null || contract.translucent == null ? null
                : unit(contract.translucent, "VOXY_TRANSLUCENT_PASS", parameters);
    }

    @Override
    public String vertexUnit() {
        if (contract == null || contract.taaOffset == null) {
            return null;
        }
        StringBuilder out = new StringBuilder("#version 460 core" + NL);
        declareUniforms(out);
        return out.append("vec2 voxy_taaOffset() ").append(contract.taaOffset).append(NL).toString();
    }

    private static final String NL = String.valueOf((char) 10);

    /**
     * The uniforms voxy.json lists, with the types Iris gives them. One Iris
     * does not know in this dimension (Complementary lists its Nether biome
     * floats everywhere) is declared a float, left at 0, as the snippet
     * still names it.
     */
    private void declareUniforms(StringBuilder out) {
        Map<String, UniformType> types = uniformTypes();
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (String name : contract.uniforms) {
            UniformType type = types.get(name);
            if (type == null) {
                unknown.add(name);
            }
            out.append("uniform ").append(type != null ? glsl(type) : "float").append(' ').append(name).append(';').append(NL);
        }
        if (!unknown.isEmpty() && unknownLogged != generation) {
            unknownLogged = generation;
            Farfield.LOG.info("Shader pack LODs: uniforms of voxy.json unknown to Iris here, declared as floats at 0: {}", unknown);
        }
    }

    private int unknownLogged = -1;

    private String unit(String snippet, String pass, String parameters) {
        StringBuilder out = new StringBuilder("#version 460 core\n");
        StringBuilder body = new StringBuilder();
        // #extension lines must come before any declaration.
        for (String line : snippet.split("\n", -1)) {
            if (line.trim().startsWith("#extension")) {
                out.append(line).append('\n');
            } else if (!line.trim().startsWith("#version")) {
                body.append(line).append('\n');
            }
        }
        out.append("#define VOXY\n#define ").append(pass).append('\n').append(parameters).append('\n');
        declareUniforms(out);
        for (Map.Entry<String, String> s : contract.samplers.entrySet()) {
            out.append("uniform ").append(s.getValue()).append(' ').append(s.getKey()).append(";\n");
        }
        return out.append(body).toString();
    }

    @Override
    public void attach(int program, String name, boolean translucent) {
        IrisRenderingPipeline iris = (IrisRenderingPipeline) pipeline;
        IrisRenderingPipelineAccessor access = (IrisRenderingPipelineAccessor) iris;
        ShaderPack pack = Iris.getCurrentPack().orElseThrow();
        CustomUniforms custom = iris.getCustomUniforms();
        Object pass = new Object();
        ProgramUniforms.Builder uniforms = ProgramUniforms.builder(name, program);
        // All of the common uniforms, then the pack's custom ones. Some names
        // come twice ("Duplicate uniform" warnings, harmless); the dynamic
        // ones alone, as Iris's DH programs, left the snippets' common
        // uniforms unset: LODs dark or white under BSL, Complementary, MakeUp.
        CommonUniforms.addCommonUniforms(uniforms, pack.getIdMap(), access.farfield$packDirectives(),
                iris.getFrameUpdateNotifier(), FogMode.PER_VERTEX);
        custom.assignTo(uniforms);
        BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniforms);
        ProgramSamplers.Builder samplers = ProgramSamplers.builder(program, reservedUnits);
        ProgramImages.Builder images = ProgramImages.builder(program);
        // Voxy's stages both run before the pack's deferred passes: the buffers as after prepare;
        // DH's water after them, as Iris's DH programs.
        boolean afterTranslucent = dhTerrain != null && translucent;
        iris.addGbufferOrShadowSamplers(samplers, images,
                afterTranslucent ? iris::getFlippedAfterTranslucent : iris::getFlippedAfterPrepare,
                false, false, true, false);
        custom.mapholderToPass(uniforms, pass);
        int[] dhUniforms = null;
        if (dhTerrain != null) {
            dhUniforms = new int[DH_UNIFORMS.length];
            for (int i = 0; i < DH_UNIFORMS.length; i++) {
                dhUniforms[i] = glGetUniformLocation(program, DH_UNIFORMS[i]);
            }
        }
        bound.put(program, new Bound(uniforms.buildUniforms(), samplers.build(), images.build(), pass, dhUniforms));
    }

    @Override
    public void bind(int program) {
        Bound b = bound.get(program);
        if (b == null) {
            return;
        }
        b.uniforms.update();
        ((IrisRenderingPipeline) pipeline).getCustomUniforms().push(b.pass);
        b.samplers.update();
        b.images.update();
        if (b.dhUniforms != null) {
            dhUniforms(b.dhUniforms);
        }
    }

    private final float[] matrix16 = new float[16];
    private final float[] matrix9 = new float[9];
    private final Matrix4f scratch = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();

    /**
     * What Iris's IrisLodRenderProgram.fillUniformData sets on a DH program:
     * the LODs' projection and the view (positions come from the camera,
     * modelOffset 0), and the game's lightmap on Iris's lightmap unit.
     */
    private void dhUniforms(int[] at) {
        matrix(at[0], lodProjection);
        matrix(at[1], lodProjection.invert(scratch));
        matrix(at[2], lodView);
        matrix(at[3], lodView.invert(scratch));
        if (at[4] >= 0) {
            lodView.invert(scratch).transpose3x3(normal);
            glUniformMatrix3fv(at[4], false, normal.get(matrix9));
        }
        if (at[5] >= 0) {
            glUniform3f(at[5], 0f, 0f, 0f);
        }
        if (at[6] >= 0) {
            glUniform1f(at[6], 0.01f);
        }
        if (at[7] >= 0) {
            glUniform1f(at[7], -1000f); // as Iris
        }
        if (at[8] >= 0) {
            glUniform1f(at[8], 0f); // the LODs give way to the game's terrain themselves (dh_bridge.frag)
        }
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        net.irisshaders.iris.gl.IrisRenderSystem.bindTextureToUnit(GL_TEXTURE_2D, IrisSamplers.LIGHTMAP_TEXTURE_UNIT,
                RenderSystem.getShaderTexture(2));
    }

    private void matrix(int location, Matrix4f m) {
        if (location >= 0) {
            glUniformMatrix4fv(location, false, m.get(matrix16));
        }
    }

    @Override
    public String[] dhSources(boolean translucent) {
        DhProgram p = translucent ? dhWater : dhTerrain;
        return p == null ? null : new String[]{p.vertex, p.fragment, p.name};
    }

    @Override
    public void detach(int program) {
        bound.remove(program);
    }

    @Override
    public void reloadPack() {
        try {
            Iris.reload();
        } catch (java.io.IOException e) {
            Farfield.LOG.warn("Shader pack LODs: the pack did not load again ({})", e.toString());
        }
    }

    @Override
    public void fail(String reason) {
        off = reason;
        Farfield.LOG.warn("LOD rendering off under shader pack {}: {}", Iris.getCurrentPackName(), reason);
    }

    private void load() {
        generation++;
        off = null;
        contract = null;
        dhTerrain = dhWater = null;
        IrisLod.dh = false;
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) {
            fail("no pack loaded");
            return;
        }
        if (!PackContract.applied()) {
            fail("the pack was loaded without a LOD contract's macro (Farfield's Iris mixin did not apply)");
            return;
        }
        if (PackContract.chosen() == PackContract.DH) {
            loadDh();
            return;
        }
        try {
            contract = VoxyContract.read(pack, Iris.getCurrentDimension());
        } catch (RuntimeException e) {
            fail(e.getMessage());
            return;
        }
        if (contract == null) {
            fail("the pack has no Voxy contract (voxy.json and voxy_opaque.glsl) for "
                    + Iris.getCurrentDimension());
            return;
        }
        Farfield.LOG.info("Shader pack LODs: {} through its Voxy contract ({}), opaque draw buffers {}, translucent {}"
                        + " ({}), blending {}, TAA jitter {}, render scale {}",
                Iris.getCurrentPackName(), contract.folder.isEmpty() ? "/" : contract.folder,
                java.util.Arrays.toString(contract.opaqueDrawBuffers),
                java.util.Arrays.toString(contract.translucentDrawBuffers),
                contract.translucent != null ? "snippet found" : "no snippet", contract.blending,
                contract.taaOffset == null ? "none" : contract.taaOffset.replaceAll("[\\s]+", " "),
                java.util.Arrays.toString(contract.renderScale));
    }

    /** The pack's DH programs, transformed by Iris as for Distant Horizons, then patched for the bridge. */
    private void loadDh() {
        IrisRenderingPipeline iris = (IrisRenderingPipeline) pipeline;
        try {
            ProgramSource terrain = iris.getDHTerrainShader().orElse(null);
            if (terrain == null) {
                fail("the pack has no dh_terrain program for " + Iris.getCurrentDimension());
                return;
            }
            dhTerrain = dhProgram(iris, terrain);
            ProgramSource water = iris.getDHWaterShader().orElse(null);
            dhWater = water != null ? dhProgram(iris, water) : dhTerrain;
        } catch (RuntimeException e) {
            dhTerrain = dhWater = null;
            fail("its DH programs do not fit the LOD bridge (" + e.getMessage() + ")");
            return;
        }
        IrisLod.dh = true;
        Farfield.LOG.info("Shader pack LODs: {} through its Distant Horizons contract, opaque draw buffers {} ({}),"
                        + " translucent {} ({})", Iris.getCurrentPackName(),
                java.util.Arrays.toString(dhTerrain.drawBuffers), dhTerrain.name,
                java.util.Arrays.toString(dhWater.drawBuffers), dhWater.name);
    }

    private static DhProgram dhProgram(IrisRenderingPipeline iris, ProgramSource source) {
        if (source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent()
                || source.getTessEvalSource().isPresent()) {
            throw new IllegalStateException(source.getName() + " has geometry or tessellation stages");
        }
        String vertex = source.getVertexSource()
                .orElseThrow(() -> new IllegalStateException(source.getName() + " has no vertex shader"));
        String fragment = source.getFragmentSource()
                .orElseThrow(() -> new IllegalStateException(source.getName() + " has no fragment shader"));
        java.util.Map<PatchShaderType, String> transformed = TransformPatcher.patchDHTerrain(source.getName(),
                vertex, null, null, null, fragment, iris.getTextureMap());
        return new DhProgram(source.getName(), DhPatch.vertex(transformed.get(PatchShaderType.VERTEX)),
                DhPatch.fragment(transformed.get(PatchShaderType.FRAGMENT)), source.getDirectives().getDrawBuffers(),
                source.getDirectives().getBlendModeOverride().orElse(null),
                source.getDirectives().getBufferBlendOverrides());
    }

    private void release() {
        releaseTargets();
        bound.clear();
        dhTerrain = dhWater = null;
        IrisLod.dh = false;
        IrisLod.opaqueDepth = 0;
        IrisLod.translucentDepth = 0;
    }

    private void releaseTargets() {
        destroy(framebuffer);
        destroy(translucentFramebuffer);
        for (int i = 0; i < extraTargets.length; i++) {
            if (extraTargets[i] != 0) {
                glDeleteTextures(extraTargets[i]);
                extraTargets[i] = 0;
            }
            IrisLod.extraTargets[i] = 0;
        }
        for (DepthTexture t : new DepthTexture[]{depth, translucentDepth}) {
            if (t != null) {
                t.destroy();
            }
        }
        framebuffer = translucentFramebuffer = null;
        depth = translucentDepth = null;
        framebufferTargets = null;
    }

    /** The GLSL types of the uniforms Iris would give a program under this pack. */
    private Map<String, UniformType> uniformTypes() {
        IrisRenderingPipeline iris = (IrisRenderingPipeline) pipeline;
        IrisRenderingPipelineAccessor access = (IrisRenderingPipelineAccessor) iris;
        TypeRecorder recorder = new TypeRecorder();
        CommonUniforms.addCommonUniforms(recorder, Iris.getCurrentPack().orElseThrow().getIdMap(),
                access.farfield$packDirectives(), iris.getFrameUpdateNotifier(), FogMode.PER_VERTEX);
        iris.getCustomUniforms().assignTo(recorder);
        BuiltinReplacementUniforms.addBuiltinReplacementUniforms(recorder);
        return recorder.types;
    }

    private static String glsl(UniformType type) {
        return switch (type) {
            case INT -> "int";
            case FLOAT -> "float";
            case MAT3 -> "mat3";
            case MAT4 -> "mat4";
            case VEC2 -> "vec2";
            case VEC2I -> "ivec2";
            case VEC3 -> "vec3";
            case VEC3I -> "ivec3";
            case VEC4 -> "vec4";
            case VEC4I -> "ivec4";
        };
    }

    /** Collects the name and type of every uniform Iris offers, binding none. */
    private static final class TypeRecorder implements DynamicLocationalUniformHolder {
        final Map<String, UniformType> types = new HashMap<>();

        @Override
        public LocationalUniformHolder addUniform(UniformUpdateFrequency updateFrequency, Uniform uniform) {
            return this;
        }

        @Override
        public OptionalInt location(String name, UniformType type) {
            types.putIfAbsent(name, type);
            return OptionalInt.empty();
        }

        @Override
        public DynamicLocationalUniformHolder addDynamicUniform(Uniform uniform, ValueUpdateNotifier notifier) {
            return this;
        }

        @Override
        public UniformHolder externallyManagedUniform(String name, UniformType type) {
            types.putIfAbsent(name, type);
            return this;
        }
    }
}
