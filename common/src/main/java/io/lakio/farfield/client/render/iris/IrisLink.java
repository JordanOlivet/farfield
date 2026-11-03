package io.lakio.farfield.client.render.iris;

import com.google.common.collect.ImmutableSet;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.render.ShaderPackLink;
import io.lakio.farfield.mixin.iris.IrisRenderingPipelineAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
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
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.targets.DepthTexture;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL44C.glClearTexImage;

/**
 * The LOD passes through a shader pack's Voxy contract, over Iris
 * (ADR-113): the pack's opaque snippet linked into {@code lod.frag}, its
 * draw buffers, a depth texture of the LODs' own that the pack's composites
 * read as {@code vxDepthTexOpaque}. Render thread only.
 */
public final class IrisLink implements ShaderPackLink {

    private final Set<Integer> reservedUnits;
    private WorldRenderingPipeline pipeline;
    private VoxyContract contract;
    /** Why the LODs are off under the current pack, or null. */
    private String off;
    private int generation;
    private DepthTexture depth;
    private GlFramebuffer framebuffer;
    private RenderTargets framebufferTargets;
    private int width;
    private int height;
    private int previousFramebuffer;
    private final int[] previousViewport = new int[4];
    private static final float[] ONE = {1f};
    private final Matrix4f lodProjection = new Matrix4f();
    private final Int2ObjectOpenHashMap<Bound> bound = new Int2ObjectOpenHashMap<>();

    /** A program's Iris uniforms, samplers and images. */
    private record Bound(ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images, Object pass) {
    }

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
            if (depth != null) {
                depth.destroy();
            }
            if (framebuffer != null) {
                framebuffer.destroy();
                framebuffer = null;
            }
            depth = new DepthTexture("Farfield LOD depth", w, h, DepthBufferFormat.DEPTH32F);
            width = w;
            height = h;
        }
        glClearTexImage(depth.getTextureId(), 0, GL_DEPTH_COMPONENT, GL_FLOAT, ONE);
        // The game's projection with the LODs' depth range, as lod.vert computes it.
        lodProjection.set(projection);
        lodProjection.m22(-(far + near) / (far - near));
        lodProjection.m32(-2f * far * near / (far - near));
        IrisLod.setFrame(lodProjection, viewRotation, distance >> 4);
        IrisLod.opaqueDepth = depth.getTextureId();
        IrisLod.translucentDepth = 0;
        if (off != null) {
            mode = NONE;
            return;
        }
        if (framebuffer == null || targets != framebufferTargets) {
            if (framebuffer != null) {
                framebuffer.destroy();
            }
            framebuffer = targets.createGbufferFramebuffer(iris.getFlippedAfterPrepare(), contract.opaqueDrawBuffers);
            // Iris attached the game's depth, a depth-stencil texture when a mod
            // enables the stencil: its stencil half stayed attached beside our
            // depth, and every LOD draw failed (GL_INVALID_FRAMEBUFFER_OPERATION).
            org.lwjgl.opengl.GL45C.glNamedFramebufferTexture(framebuffer.getId(),
                    org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT, 0, 0);
            framebuffer.addDepthAttachment(depth.getTextureId());
            framebufferTargets = targets;
            Farfield.LOG.info("Shader pack LODs: target {}x{} built, status {}", w, h, Integer.toHexString(
                    org.lwjgl.opengl.GL45C.glCheckNamedFramebufferStatus(framebuffer.getId(), GL_DRAW_FRAMEBUFFER)));
        }
        mode = VOXY;
    }

    @Override
    public int begin() {
        if (mode != VOXY) {
            return mode;
        }
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
        framebuffer.bind();
        glViewport(0, 0, width, height);
        return VOXY;
    }

    @Override
    public void end() {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
    }

    @Override
    public int generation() {
        return generation;
    }

    @Override
    public String opaqueUnit(String parameters) {
        if (contract == null) {
            return null;
        }
        StringBuilder out = new StringBuilder("#version 460 core\n");
        StringBuilder body = new StringBuilder();
        // #extension lines must come before any declaration.
        for (String line : contract.opaque.split("\n", -1)) {
            if (line.trim().startsWith("#extension")) {
                out.append(line).append('\n');
            } else if (!line.trim().startsWith("#version")) {
                body.append(line).append('\n');
            }
        }
        out.append("#define VOXY\n#define VOXY_OPAQUE_PASS\n").append(parameters).append('\n');
        Map<String, UniformType> types = uniformTypes();
        for (String name : contract.uniforms) {
            UniformType type = types.get(name);
            if (type != null) {
                out.append("uniform ").append(glsl(type)).append(' ').append(name).append(";\n");
            } else {
                Farfield.LOG.warn("Shader pack LODs: uniform {} of voxy.json unknown to Iris, not declared", name);
            }
        }
        for (Map.Entry<String, String> s : contract.samplers.entrySet()) {
            out.append("uniform ").append(s.getValue()).append(' ').append(s.getKey()).append(";\n");
        }
        return out.append(body).toString();
    }

    @Override
    public void attach(int program, String name) {
        IrisRenderingPipeline iris = (IrisRenderingPipeline) pipeline;
        IrisRenderingPipelineAccessor access = (IrisRenderingPipelineAccessor) iris;
        ShaderPack pack = Iris.getCurrentPack().orElseThrow();
        CustomUniforms custom = iris.getCustomUniforms();
        Object pass = new Object();
        ProgramUniforms.Builder uniforms = ProgramUniforms.builder(name, program);
        CommonUniforms.addCommonUniforms(uniforms, pack.getIdMap(), access.farfield$packDirectives(),
                iris.getFrameUpdateNotifier(), FogMode.PER_VERTEX);
        custom.assignTo(uniforms);
        BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniforms);
        ProgramSamplers.Builder samplers = ProgramSamplers.builder(program, reservedUnits);
        ProgramImages.Builder images = ProgramImages.builder(program);
        iris.addGbufferOrShadowSamplers(samplers, images, iris::getFlippedAfterPrepare, false, false, true, false);
        custom.mapholderToPass(uniforms, pass);
        bound.put(program, new Bound(uniforms.buildUniforms(), samplers.build(), images.build(), pass));
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
    }

    @Override
    public void detach(int program) {
        bound.remove(program);
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
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) {
            fail("no pack loaded");
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
        Farfield.LOG.info("Shader pack LODs: {} through its Voxy contract ({}), opaque draw buffers {}",
                Iris.getCurrentPackName(), contract.folder.isEmpty() ? "/" : contract.folder,
                java.util.Arrays.toString(contract.opaqueDrawBuffers));
    }

    private void release() {
        if (framebuffer != null) {
            framebuffer.destroy();
            framebuffer = null;
        }
        if (depth != null) {
            depth.destroy();
            depth = null;
        }
        framebufferTargets = null;
        bound.clear();
        IrisLod.opaqueDepth = 0;
        IrisLod.translucentDepth = 0;
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
