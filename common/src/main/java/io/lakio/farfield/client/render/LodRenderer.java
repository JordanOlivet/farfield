package io.lakio.farfield.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.LodWorldProvider;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.BinaryGreedyMesher;
import io.lakio.farfield.core.mesh.FaceDirections;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.MeshBuffer;
import io.lakio.farfield.core.mesh.Quad;
import io.lakio.farfield.core.mesh.SurfaceBiomes;
import io.lakio.farfield.core.select.LodSelector;
import io.lakio.farfield.core.util.RangeAllocator;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.RenderSettings;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.LongConsumer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Draws the LOD sections of the current dimension beyond the vanilla render
 * distance (plan item E.4), on the minimal OpenGL 4.6 RHI (E.3).
 *
 * <ul>
 *   <li>Data: the {@link LodWorld} that {@link LodWorldProvider} gives for
 *       the current dimension (ADR-022).</li>
 *   <li>Selection: {@link LodSelector} with a screen-space criterion: a
 *       section splits into the finer level when its voxels would exceed
 *       {@link RenderSettings#voxelPixels()} pixels on screen (from the
 *       window height and the field of view). Run again only when the camera
 *       moved 8 blocks, meshes arrived or left, the store changed, the
 *       detail changed, or once a second; between runs each frame only culls the cached list against
 *       the frustum. Section existence is cached (the store's listener keeps
 *       it exact). Missing meshes are built by {@link BinaryGreedyMesher} on
 *       two low-priority threads and uploaded on the render thread under a
 *       per-frame budget; a section the server rewrites is meshed again.</li>
 *   <li>Noise preview (ADR-039, {@link PreviewLayer}): its own selection
 *       over the preview tiles, at every level, with sections built from the
 *       tiles on the mesher threads; chunks with real data are masked out. A
 *       tile written again, or real data arriving in it, meshes its resident
 *       preview sections again.</li>
 *   <li>Steady frames allocate nothing (primitive collections, reused
 *       matrices and buffers), so they add no garbage-collection pauses.</li>
 *   <li>GPU: quads in one storage buffer (first-fit ranges, grown on
 *       demand), one 32-byte record per resident section, baked face
 *       textures and their mean colors per block state and face
 *       ({@link MaterialColors}, {@link FaceTextures}), tints per biome
 *       ({@link BiomeTints}). Textures show up to
 *       {@link RenderSettings#textureDistance()} and fade into the mean
 *       colors over its last quarter. Indexed indirect draws ({@code glMultiDrawElementsIndirect}, 4 vertices per quad)
 *       per frame: opaque quads, then translucent ones (water, glass, ice)
 *       blended with their texture coverage, without depth writes; each
 *       section stores its opaque quads first, each pass grouped by face
 *       direction, and only the directions that can face the camera are
 *       drawn ({@link FaceDirections}): beyond its box on every axis, a
 *       section sends three of its six. Vertex pulling in
 *       {@code lod.vert}, back faces culled. GPU time is measured with
 *       timestamp queries and logged with the render-thread time.</li>
 *   <li>Sections with translucent voxels are meshed with their six
 *       same-level neighbors' border layers, so water spanning sections
 *       shows no inner walls; a rewritten section meshes its translucent
 *       neighbors again.</li>
 *   <li>Ambient occlusion as vanilla's smooth lighting, per fragment: the
 *       opaque voxels of each section (4 KB) go to a storage buffer by
 *       slot, with the slots of its 26 same-level neighbors; {@code lod.frag}
 *       reads the eight voxels around the one in front of the face, across
 *       section borders, so merged quads stay merged and no grid of lighter
 *       lines shows along section borders.</li>
 *   <li>Hand-off: drawn after the sky and before the terrain, with its own
 *       depth range; the depth buffer is cleared afterwards so vanilla
 *       terrain covers it, and fragments within the vanilla distance are
 *       discarded. Vanilla's terrain fog moves out to the LOD fog
 *       ({@code FogRendererMixin}), so near terrain does not fade into the
 *       sky just before the LODs start.</li>
 * </ul>
 */
public final class LodRenderer {

    private static final LodRenderer INSTANCE = new LodRenderer();

    private static final double MAX_DISTANCE = 4096;
    private static final float FOG_START = (float) MAX_DISTANCE * 0.6f;
    private static final float FOG_END = (float) MAX_DISTANCE;
    private static final int MAX_IN_FLIGHT = 64;
    private static final long UPLOAD_BUDGET_QUADS = 1 << 20;
    private static final long INITIAL_QUADS = 8L << 20;
    private static final long MAX_QUADS = 128L << 20;
    private static final int SECTION_BYTES = 32;
    /** The compiled-sections grid is rebuilt every this many frames. */
    private static final int DRAWN_SECTIONS_EVERY = 8;
    /** Chunks of the outer ring of that grid read again every frame. */
    private static final int RING_CHUNKS = 3;
    private static final int OCCUPANCY_INTS = LodSection.SIZE * LodSection.SIZE;
    /** Neighbor slots per section slot: (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1), slot + 1 or 0. */
    private static final int NEIGHBOR_INTS = 27;
    /** Surface biome per 4x4-voxel column and section slot, two 16-bit entries per int (SurfaceBiomes). */
    private static final int BIOME_COLUMN_INTS = LodSection.BIOME_CELLS_PER_AXIS * LodSection.BIOME_CELLS_PER_AXIS / 2;
    /** A resident mesh the selection has not used for this long is freed. */
    private static final long EVICT_AFTER_NANOS = 10_000_000_000L;
    /** The selection runs again when the camera moved this far (squared blocks)... */
    private static final double RESELECT_DISTANCE_SQ = 8 * 8;
    /** ...or after this long anyway. */
    private static final long RESELECT_NANOS = 1_000_000_000L;
    private static final byte EXISTS = 1;
    private static final byte ABSENT = 2;
    private static final int TIMER_SLOTS = 4;
    private static final int TIMESTAMPS = 4;
    private static final int[][] STEP = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    /**
     * Quad groups of a section, in range order: opaque quads by direction,
     * then translucent ones by direction, so a pass draws only the directions
     * that can face the camera ({@link FaceDirections}).
     */
    private static final int GROUPS = 2 * Quad.DIRECTIONS;
    /** An indexed indirect command: count, instances, first index, base vertex, base instance (the slot). */
    private static final int COMMAND_INTS = 5;
    private static final int COMMAND_BYTES = COMMAND_INTS * 4;
    /**
     * Quads the shared index buffer describes: 4 vertices and 6 indices
     * each, so the vertex shader runs 4 times per quad instead of 6 (the
     * GPU reuses the shared corners). A longer run of quads takes several
     * commands.
     */
    private static final int INDEXED_QUADS = 1 << 17;
    /** Command lists: opaque quads of sections that cannot discard a fragment... */
    private static final int SOLID = 0;
    /** ...opaque quads of sections that may (near the game's terrain, or faces with holes)... */
    private static final int DISCARD = 1;
    /** ...and translucent quads. */
    private static final int TRANSLUCENT = 2;

    private boolean initialized;
    private boolean disabled;
    /** One pass, discards in the shaded shader (the depth pre-pass switched off). */
    private Shader plain;
    /** Opaque depth pre-pass: only the hand-off and cutout discards. */
    private Shader depthOnly;
    /** Shaded passes after the pre-pass, with early depth tests. */
    private Shader earlyZ;
    /** Opaque depth pre-pass of sections that cannot discard: no discard, so early depth tests. */
    private Shader depthSolid;
    private GlBuffer quads;
    private GlBuffer sectionInfo;
    private GlBuffer colorBuffer;
    private GlBuffer biomeBuffer;
    /** Opaque voxels per section slot ({@link #OCCUPANCY_INTS} ints), for ambient occlusion. */
    private GlBuffer occupancy;
    /** Per section slot, the slots of its resident same-level neighbors ({@link #NEIGHBOR_INTS}). */
    private GlBuffer neighborSlots;
    /** Per section slot, its columns' surface biomes ({@link #BIOME_COLUMN_INTS}), for biome blending. */
    private GlBuffer biomeColumns;
    private final int[] neighborScratch = new int[NEIGHBOR_INTS];
    private final int[] oneInt = new int[1];
    private final LongArrayList evicted = new LongArrayList();
    /**
     * Sections the terrain renderer has compiled around the camera, one bit
     * each ({@link #updateDrawnSections}): LODs give way only where the game
     * really draws, so chunks not built yet (the outer ring waiting for its
     * neighbors, everything right after a teleport) keep their LODs.
     */
    private GlBuffer drawnSections;
    private int[] drawnBits = new int[0];
    /** Raw compiled state of the last pass, same layout: a section must stay built for two passes. */
    private int[] compiledBits = new int[0];
    private final BlockPos.MutableBlockPos sectionPos = new BlockPos.MutableBlockPos();
    private int gridMinX;
    private int gridMinY;
    private int gridMinZ;
    private int gridWidth = -1;
    private int gridHeight;
    private GlBuffer indirect;
    /** The shared index buffer of {@link #INDEXED_QUADS} quads. */
    private GlBuffer quadIndices;
    private int vao;
    private RangeAllocator arena;
    /** Texture unit of the face atlas; the game tracks units 0 to 11 and uses the low ones. */
    private static final int ATLAS_UNIT = 10;
    /** Texture unit of vanilla's lightmap for the LOD shader. */
    private static final int LIGHTMAP_UNIT = 11;

    private final FaceTextures faceTextures = new FaceTextures();
    private final MaterialColors colors = new MaterialColors(faceTextures);
    private int atlas;
    private final BiomeTints tints = new BiomeTints();
    private final IntPredicate tintedMaterials = colors::tinted;
    /** Changes on every resource reload: the colors are built again. */
    private BakedModel missingModel;
    private ClientLevel colorLevel;
    /** Sodium or a fork draws the terrain: its section rule decides where LODs give way. */
    private boolean sodiumRule;
    private static boolean pushedFog;
    private static boolean followVanillaFog;
    private static boolean thickFogDimension;
    /** Fog distance meaning no fog: beyond anything drawn. */
    private static final float NO_FOG = 1e7f;
    private double lastSelectionDistance = -1;
    /** Per slot: before the passes, after the depth pre-pass, after the opaque pass, at the end. */
    private final int[] timerQueries = new int[TIMER_SLOTS * TIMESTAMPS];
    private final boolean[] timerPending = new boolean[TIMER_SLOTS];
    private int timerSlot;

    private LodWorld world;
    private final AtomicInteger generation = new AtomicInteger();
    private final LodSelector selector = new LodSelector(LodWorld.LEVELS, 4.0);
    private final Long2ObjectOpenHashMap<Resident> resident = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet requested = new LongOpenHashSet();
    private final LongOpenHashSet stale = new LongOpenHashSet();
    /** Sections meshed before the looks of their materials were built: meshed again once they are. */
    private final LongOpenHashSet provisional = new LongOpenHashSet();
    private final Long2ByteOpenHashMap existence = new Long2ByteOpenHashMap();
    private final ConcurrentLinkedQueue<MeshResult> done = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> rewritten = new ConcurrentLinkedQueue<>();
    /** Decoded sections for the mesher threads; the store's writes invalidate them. */
    private final SectionCache sectionCache = new SectionCache();
    private final LongConsumer listener = key -> {
        sectionCache.invalidate(key);
        rewritten.add(key);
    };
    /** Time the mesher threads spent per job, and in it reading sections, since the last stat line. */
    private final java.util.concurrent.atomic.AtomicLong meshNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong meshReadNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger meshJobs = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ThreadPoolExecutor meshers =
            (java.util.concurrent.ThreadPoolExecutor) Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "farfield-mesher");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private final ThreadLocal<BinaryGreedyMesher> mesher = ThreadLocal.withInitial(BinaryGreedyMesher::new);
    private final ThreadLocal<MeshBuffer> meshBuffer = ThreadLocal.withInitial(MeshBuffer::new);
    private final ThreadLocal<int[][]> borderPlanes = ThreadLocal.withInitial(() -> new int[6][LodSection.SIZE]);
    private final ThreadLocal<int[]> surfaceScratch =
            ThreadLocal.withInitial(() -> new int[LodSection.BIOME_CELLS_PER_AXIS * LodSection.BIOME_CELLS_PER_AXIS]);
    private final ThreadLocal<byte[][]> borderLights =
            ThreadLocal.withInitial(() -> new byte[6][LodSection.SIZE * LodSection.SIZE]);
    private int[] freeSlots = new int[0];
    private int freeSlotCount;
    private int slotCount;

    // Cached selection and per-frame scratch, reused to keep steady frames allocation-free.
    private final LongArrayList drawList = new LongArrayList();
    private final LongConsumer drawSink = drawList::add;
    private final LongConsumer wantSink = this::request;
    private final LodSelector.Sections sections = new LodSelector.Sections() {
        @Override
        public boolean exists(long key) {
            return sectionExists(key);
        }

        @Override
        public boolean ready(long key) {
            // A section the selection relies on to descend is in use even when
            // only its children are drawn: freeing it would make its parent fall
            // back to the coarse level until it is meshed again (flicker).
            Resident r = resident.get(key);
            if (r != null) {
                r.lastUsed = selectionTime;
            }
            return r != null;
        }
    };
    private boolean selectionDirty = true;
    /** The noise preview setting the last selection ran with. */
    private boolean selectedPreview;
    private final PreviewLayer previewLayer = new PreviewLayer(meshers);
    private final LongOpenHashSet changedTiles = new LongOpenHashSet();
    /** Most sections a selection drew coarser than wanted since the last stat line. */
    private int maxFallbacks;
    private double loggedDetail = 1e-9;
    private double pendingDetail = 1e-9;
    private long selectionTime;
    private double selectedX;
    private double selectedY;
    private double selectedZ;
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f cullMatrix = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();
    private final float[] matrix = new float[16];
    /** Draw commands per list ({@link #SOLID}, {@link #DISCARD}, {@link #TRANSLUCENT}), in indirect buffer order. */
    private final IntBuffer[] commands = {
            MemoryUtil.memAllocInt(4 * 1024), MemoryUtil.memAllocInt(4 * 1024), MemoryUtil.memAllocInt(4 * 1024)};
    private final int[] draws = new int[3];

    // Statistics for the periodic log line.
    private long frame;
    private long frameNanos;
    private long timedFrames;
    /** GPU time of the depth pre-pass, the opaque pass and the translucent pass. */
    private final long[] gpuPassNanos = new long[TIMESTAMPS - 1];
    private long gpuFrames;
    private int selections;
    private int meshesUploaded;
    private int meshesEvicted;
    /** Quads of the drawn sections, and those of the directions that can face the camera (sent to the GPU). */
    private long quadsInView;
    private long quadsSubmitted;
    /** Drawn sections in the pre-pass that may discard ({@link #DISCARD}). */
    private long discardSections;
    /** Sections in view the game draws entirely, skipped ({@link #gameDrawsAll}). */
    private long leftToGame;
    private long nextStats;

    /** A variant of the LOD program and its uniform locations. */
    private static final class Shader {
        final GlProgram program;
        final int viewProj;
        final int cameraBlock;
        final int cameraFrac;
        final int depthRange;
        final int renderDistance;
        final int gridMin;
        final int gridSize;
        final int sodiumRule;
        final int lightmap;
        final int fogColor;
        final int fog;
        final int fogCylinder;
        final int translucent;
        final int debugLevels;
        final int biomeBlend;
        final int atlas;
        final int atlasSize;
        final int textureEnd;

        Shader(String defines) {
            program = new GlProgram("lod.vert", "lod.frag", defines);
            viewProj = program.uniform("u_viewProj");
            cameraBlock = program.uniform("u_cameraBlock");
            cameraFrac = program.uniform("u_cameraFrac");
            depthRange = program.uniform("u_depthRange");
            renderDistance = program.uniform("u_renderDistance");
            gridMin = program.uniform("u_gridMin");
            gridSize = program.uniform("u_gridSize");
            sodiumRule = program.uniform("u_sodiumRule");
            lightmap = program.uniform("u_lightmap");
            fogColor = program.uniform("u_fogColor");
            fog = program.uniform("u_fog");
            fogCylinder = program.uniform("u_fogCylinder");
            translucent = program.uniform("u_translucent");
            debugLevels = program.uniform("u_debugLevels");
            biomeBlend = program.uniform("u_biomeBlend");
            atlas = program.uniform("u_atlas");
            atlasSize = program.uniform("u_atlasSize");
            textureEnd = program.uniform("u_textureEnd");
        }
    }

    private static final class Resident {
        final int slot;
        long start;
        int quadCount;
        /** The first quads of the range are opaque, the rest translucent. */
        int opaqueCount;
        /** Start of each quad group in the range ({@link #GROUPS}), then {@link #quadCount}. */
        int[] groups = new int[GROUPS + 1];
        /** Some opaque faces have holes to cut out (or were meshed before their colors were built). */
        boolean cutout;
        /** Its opaque voxels are in the occupancy buffer (ambient occlusion). */
        boolean occluders;
        /** Last time the selection used it: drawn, or relied on to descend to its children. */
        long lastUsed;

        Resident(int slot) {
            this.slot = slot;
        }
    }

    /** {@code groups}: start of each quad group ({@link #GROUPS}), then the quad count. */
    /** {@code provisional}: {@code cutout} was guessed for materials whose looks were not built yet. */
    private record MeshResult(int generation, long key, long[] quads, int[] groups, boolean cutout,
                              boolean provisional, int[] occupancy, int[] biomeColumns) {
    }

    private static final int[] NO_GROUPS = new int[GROUPS + 1];

    private LodRenderer() {
    }

    /** Whether LODs are drawn beyond the vanilla terrain (a LOD world is attached and rendering is on). */
    public static boolean drawsBeyondVanilla() {
        LodRenderer r = INSTANCE;
        return r.world != null && r.initialized && !r.disabled && RenderSettings.enabled();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, LodRenderer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Set by the fog mixin for every terrain frame (ADR-033, ADR-034).
     *
     * @param pushed          the terrain fog was moved out to the player's fog distance
     * @param followVanilla   the LODs take the game's fog as it is (thick dimension fog
     *                        kept, water, lava, powder snow)
     * @param thickDimension  the dimension has thick fog (the Nether)
     */
    public static void setFogState(boolean pushed, boolean followVanilla, boolean thickDimension) {
        pushedFog = pushed;
        followVanillaFog = followVanilla;
        thickFogDimension = thickDimension;
    }

    /**
     * Moves the game's terrain fog out to a fog distance (ADR-034): opaque at
     * the distance (never closer than the far plane), from 60 % of it; for
     * normal fog never before vanilla's own fog start, so near terrain stays
     * as clear as without LODs. {@link RenderConfig#FOG_OFF}: no fog.
     */
    public static void pushFog(int distance, float farPlane, boolean normal) {
        if (distance == RenderConfig.FOG_OFF) {
            RenderSystem.setShaderFogStart(NO_FOG);
            RenderSystem.setShaderFogEnd(NO_FOG * 2);
            return;
        }
        float end = Math.max(distance, farPlane);
        float start = end * 0.6f;
        if (normal) {
            start = Math.max(start, farPlane - Mth.clamp(farPlane / 10f, 4f, 64f));
        }
        RenderSystem.setShaderFogStart(Math.min(start, end));
        RenderSystem.setShaderFogEnd(end);
    }

    /**
     * How far sections are selected: nothing is drawn or kept beyond the
     * fog, which hides it anyway (water and lava fog excepted: they come and
     * go, and would evict every mesh).
     */
    private double selectionDistance() {
        int distance = thickFogDimension ? RenderSettings.thickFogDistance() : RenderSettings.fogDistance();
        if (distance == RenderConfig.FOG_OFF) {
            return MAX_DISTANCE;
        }
        if (distance == RenderConfig.FOG_VANILLA) {
            return 0; // the game's thick fog is opaque within its render distance
        }
        return Math.min(MAX_DISTANCE, distance);
    }

    /** Called from the level renderer mixin, after the sky, before the terrain. */
    public static void render(Camera camera, Matrix4f viewRotation, Matrix4f projection) {
        INSTANCE.renderFrame(camera, viewRotation, projection);
    }

    private boolean init() {
        if (initialized) {
            return !disabled;
        }
        initialized = true;
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.OpenGL46) {
            disabled = true;
            Farfield.LOG.warn("LOD rendering disabled: OpenGL 4.6 is not available ({})",
                    glGetString(GL_VERSION));
            return false;
        }
        try {
            plain = new Shader("");
            depthOnly = new Shader("#define DEPTH_ONLY\n");
            earlyZ = new Shader("#define EARLY_Z\n");
            depthSolid = new Shader("#define DEPTH_ONLY\n#define SOLID\n");
        } catch (RuntimeException e) {
            disabled = true;
            Farfield.LOG.error("LOD rendering disabled: shader setup failed", e);
            return false;
        }
        sodiumRule = classExists("net.caffeinemc.mods.sodium.client.SodiumClientMod")
                || classExists("org.embeddedt.embeddium.impl.Embeddium")
                || classExists("me.jellysquid.mods.sodium.client.SodiumClientMod");
        Farfield.LOG.info("LOD hand-off follows the {} section rule", sodiumRule ? "Sodium" : "vanilla");
        glGenQueries(timerQueries);
        quads = new GlBuffer(INITIAL_QUADS * 8);
        arena = new RangeAllocator(INITIAL_QUADS);
        sectionInfo = new GlBuffer(4096L * SECTION_BYTES);
        colorBuffer = new GlBuffer(4096L * MaterialColors.STRIDE * 4);
        biomeBuffer = new GlBuffer(256L * BiomeTints.KINDS * 4);
        occupancy = new GlBuffer(1024L * OCCUPANCY_INTS * 4);
        neighborSlots = new GlBuffer(1024L * NEIGHBOR_INTS * 4);
        biomeColumns = new GlBuffer(1024L * BIOME_COLUMN_INTS * 4);
        drawnSections = new GlBuffer(4096L * 4);
        indirect = new GlBuffer(1024L * COMMAND_BYTES);
        vao = glCreateVertexArrays();
        int[] indices = new int[INDEXED_QUADS * 6];
        for (int q = 0; q < INDEXED_QUADS; q++) {
            // Corners: 0 (0, 0), 1 (1, 0), 2 (0, 1), 3 (1, 1) along the face's (U, V).
            int v = q * 4;
            int i = q * 6;
            indices[i] = v;
            indices[i + 1] = v + 1;
            indices[i + 2] = v + 3;
            indices[i + 3] = v;
            indices[i + 4] = v + 3;
            indices[i + 5] = v + 2;
        }
        quadIndices = new GlBuffer((long) indices.length * 4);
        quadIndices.upload(0, indices);
        glVertexArrayElementBuffer(vao, quadIndices.id());
        Farfield.LOG.info("LOD renderer ready on {}, OpenGL {} ({} MB of quads to start)", glGetString(GL_RENDERER),
                glGetString(GL_VERSION), INITIAL_QUADS * 8 >> 20);
        return true;
    }

    private void renderFrame(Camera camera, Matrix4f viewRotation, Matrix4f projection) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !RenderSettings.enabled() || !init()) {
            return;
        }
        LodWorld current = LodWorldProvider.current(mc, level);
        if (current != world) {
            switchWorld(current);
        }
        if (world == null) {
            return;
        }
        long frameStart = System.nanoTime();
        RenderSettings.resize(meshers, RenderSettings.effectiveThreads(RenderSettings.meshThreads()), "LOD mesher");
        frame++;
        readGpuTimers();
        applyRewrites();
        uploadMeshes();
        uploadColors(mc, level);
        updateDrawnSections(mc, level, camera);

        Vec3 cam = camera.getPosition();
        double dx = cam.x - selectedX;
        double dy = cam.y - selectedY;
        double dz = cam.z - selectedZ;
        updateDetail(mc, projection);
        double distance = selectionDistance();
        if (distance != lastSelectionDistance) {
            lastSelectionDistance = distance;
            selectionDirty = true;
        }
        if (RenderSettings.noisePreview() != selectedPreview) {
            selectionDirty = true;
        }
        if (selectionDirty || dx * dx + dy * dy + dz * dz > RESELECT_DISTANCE_SQ
                || frameStart - selectionTime > RESELECT_NANOS) {
            select(level, cam, frameStart);
        }

        updateFrustum(viewRotation, projection);
        for (int l = 0; l < commands.length; l++) {
            commands[l].clear();
            draws[l] = 0;
        }
        int count = 0;
        int renderDistance = mc.options.getEffectiveRenderDistance();
        for (int i = 0, n = drawList.size(); i < n; i++) {
            long key = drawList.getLong(i);
            Resident r = resident.get(key);
            if (r == null || r.quadCount == 0) {
                continue;
            }
            int faces = visibleFaces(key, cam);
            if (faces == 0) {
                continue;
            }
            if (gameDrawsAll(key, cam, renderDistance)) {
                leftToGame++; // every fragment would give way to the game's terrain
                continue;
            }
            count++;
            quadsInView += r.quadCount;
            boolean discards = r.cutout || nearGame(key);
            discardSections += discards ? 1 : 0;
            addCommands(r, discards ? DISCARD : SOLID, faces);
            addCommands(r, TRANSLUCENT, faces);
        }
        if (frame % 60 == 0) {
            evictUnused(frameStart);
        }
        if (count > 0) {
            draw(cam, viewRotation, projection, mc);
        }
        frameNanos += System.nanoTime() - frameStart;
        timedFrames++;
        if (frameStart > nextStats) {
            logStats(count);
        }
    }

    /**
     * Rebuilds the grid of sections the terrain renderer has compiled, over
     * the render distance plus one chunk around the camera and the level's
     * height. {@code LevelRenderer.isSectionCompiled} is vanilla's state, or
     * Sodium's (it overrides the method).
     */
    private void updateDrawnSections(Minecraft mc, ClientLevel level, Camera camera) {
        int rd = mc.options.getEffectiveRenderDistance() + 1;
        BlockPos cam = camera.getBlockPosition();
        int minX = (cam.getX() >> 4) - rd;
        int minZ = (cam.getZ() >> 4) - rd;
        int minY = level.getMinSection();
        int width = rd * 2 + 1;
        int height = level.getSectionsCount();
        // The whole grid every few frames or when it moves; else only the outer
        // ring, where chunks load, build and unload while the camera moves.
        boolean full = frame % DRAWN_SECTIONS_EVERY == 1;
        if (minX != gridMinX || minZ != gridMinZ || minY != gridMinY || width != gridWidth || height != gridHeight) {
            remapCompiled(minX, minY, minZ, width, height);
            full = true;
        }
        int bits = gridWidth * gridWidth * gridHeight;
        int ints = (bits + 31) >> 5;
        int ring = rd - RING_CHUNKS;
        net.minecraft.client.renderer.LevelRenderer renderer = mc.levelRenderer;
        for (int z = 0; z < gridWidth; z++) {
            for (int x = 0; x < gridWidth; x++) {
                if (!full && Math.max(Math.abs(x - rd), Math.abs(z - rd)) < ring) {
                    continue;
                }
                int column = (z * gridWidth + x) * gridHeight;
                for (int y = 0; y < gridHeight; y++) {
                    sectionPos.set((gridMinX + x) << 4, (gridMinY + y) << 4, (gridMinZ + z) << 4);
                    int bit = column + y;
                    int mask = 1 << (bit & 31);
                    boolean compiled = renderer.isSectionCompiled(sectionPos);
                    boolean before = (compiledBits[bit >> 5] & mask) != 0;
                    // A section built this frame enters Sodium's render lists only on
                    // the next one: LODs give way once it has been built for two
                    // passes, else the area showed neither for a frame.
                    if (compiled && before) {
                        drawnBits[bit >> 5] |= mask;
                    } else {
                        drawnBits[bit >> 5] &= ~mask;
                    }
                    if (compiled) {
                        compiledBits[bit >> 5] |= mask;
                    } else {
                        compiledBits[bit >> 5] &= ~mask;
                    }
                }
            }
        }
        long needed = (long) ints * 4;
        if (needed > drawnSections.size()) {
            drawnSections.grow(Math.max(needed, drawnSections.size() * 2), 0);
        }
        drawnSections.upload(0, java.util.Arrays.copyOf(drawnBits, ints));
    }

    /** Moves the grids to a new origin or size, keeping the compiled state of sections still inside. */
    private void remapCompiled(int minX, int minY, int minZ, int width, int height) {
        int ints = (width * width * height + 31) >> 5;
        int[] compiled = new int[ints];
        if (gridWidth > 0 && minY == gridMinY && height == gridHeight) {
            for (int z = 0; z < width; z++) {
                int oz = z + minZ - gridMinZ;
                if (oz < 0 || oz >= gridWidth) {
                    continue;
                }
                for (int x = 0; x < width; x++) {
                    int ox = x + minX - gridMinX;
                    if (ox < 0 || ox >= gridWidth) {
                        continue;
                    }
                    int from = (oz * gridWidth + ox) * gridHeight;
                    int to = (z * width + x) * height;
                    for (int y = 0; y < height; y++) {
                        int f = from + y;
                        if ((compiledBits[f >> 5] >>> (f & 31) & 1) != 0) {
                            int t = to + y;
                            compiled[t >> 5] |= 1 << (t & 31);
                        }
                    }
                }
            }
        }
        compiledBits = compiled;
        drawnBits = new int[ints];
        gridMinX = minX;
        gridMinY = minY;
        gridMinZ = minZ;
        gridWidth = width;
        gridHeight = height;
    }

    /**
     * Screen-space level of detail: a section of S blocks at distance D
     * covers about {@code S * focal / D} pixels, so its 32 voxels stay under
     * {@code voxelPixels} each while {@code D > S * focal / (32 * voxelPixels)}.
     */
    private void updateDetail(Minecraft mc, Matrix4f projection) {
        float focal = mc.getWindow().getHeight() * Math.abs(projection.m11()) / 2;
        if (!Float.isFinite(focal) || focal <= 0) {
            return; // degenerate projection (seen on the first frame): keep the last detail
        }
        double detail = Math.max(1, focal / (LodSection.SIZE * RenderSettings.voxelPixels()));
        // A value must hold for two frames: the first frame's projection can be off
        // (seen at 20 times the right detail), which would request level-0 meshes for nothing.
        if (Math.abs(detail - pendingDetail) > pendingDetail * 0.02) {
            pendingDetail = detail;
            return;
        }
        double current = selector.detail();
        if (Math.abs(detail - current) > current * 0.02) {
            selector.setDetail(detail);
            previewLayer.setDetail(detail);
            selectionDirty = true;
            if (Math.abs(detail - loggedDetail) > loggedDetail * 0.1) {
                loggedDetail = detail;
                Farfield.LOG.info("LOD detail: {} px per voxel, focal {} px (window height {}, projection m11 {}),"
                                + " sections split within {} section sizes", RenderSettings.voxelPixels(), focal,
                        mc.getWindow().getHeight(), projection.m11(), String.format("%.1f", detail));
            }
        }
    }

    private void select(ClientLevel level, Vec3 cam, long now) {
        selectionTime = now;
        selectedX = cam.x;
        selectedY = cam.y;
        selectedZ = cam.z;
        selectionDirty = false;
        selections++;
        drawList.clear();
        selector.select(cam.x, cam.y, cam.z, level.getMinBuildHeight(), level.getMaxBuildHeight(), lastSelectionDistance,
                sections, drawSink, wantSink);
        maxFallbacks = Math.max(maxFallbacks, selector.fallbacks());
        selectedPreview = RenderSettings.noisePreview();
        if (selectedPreview) {
            previewLayer.select(cam.x, cam.y, cam.z, level.getMinBuildHeight(), level.getMaxBuildHeight(),
                    lastSelectionDistance, sections::ready, drawSink, wantSink);
        }
        for (int i = 0, n = drawList.size(); i < n; i++) {
            Resident r = resident.get(drawList.getLong(i));
            if (r != null) {
                r.lastUsed = now;
            }
        }
    }

    /** The level a section is drawn at (preview keys carry an offset). */
    private static int drawLevel(long key) {
        return PreviewLayer.drawLevel(key);
    }

    private boolean sectionExists(long key) {
        byte known = existence.get(key);
        if (known != 0) {
            return known == EXISTS;
        }
        boolean exists;
        try {
            exists = world.exists(key);
        } catch (IOException e) {
            exists = false;
        }
        existence.put(key, exists ? EXISTS : ABSENT);
        return exists;
    }

    private void logStats(int count) {
        nextStats = System.nanoTime() + 30_000_000_000L;
        int jobs = meshJobs.getAndSet(0);
        long jobNanos = meshNanos.getAndSet(0);
        long readNanos = meshReadNanos.getAndSet(0);
        long[] cacheCounts = sectionCache.takeCounts();
        if (jobs > 0) {
            Farfield.LOG.info("LOD meshing: {} jobs on {} threads, {} us per job, {} us of it reading sections, {} %"
                            + " of {} section reads from the cache", jobs, meshers.getCorePoolSize(),
                    jobNanos / 1000 / jobs, readNanos / 1000 / jobs,
                    cacheCounts[1] == 0 ? 0 : cacheCounts[0] * 100 / cacheCounts[1], cacheCounts[1]);
        }
        Farfield.LOG.info("LOD renderer: {} sections drawn, {} resident, {} MB of {} MB quads used, {} meshing,"
                        + " {} meshes uploaded and {} freed, {} selections since last line; per frame {} us on the"
                        + " render thread and {} us on the GPU (depth pre-pass {}, opaque {}, translucent {}), {} of"
                        + " {} quads sent (face directions), {} sections may discard, {} left to the game, over {}"
                        + " frames; detail {}; at most {} coarse fallbacks per selection", count, resident.size(),
                arena.used() * 8 >> 20, arena.capacity() * 8 >> 20, requested.size(), meshesUploaded, meshesEvicted,
                selections, frameNanos / 1000 / Math.max(1, timedFrames),
                (gpuPassNanos[0] + gpuPassNanos[1] + gpuPassNanos[2]) / 1000 / Math.max(1, gpuFrames),
                RenderSettings.depthPrepass() ? gpuPassNanos[0] / 1000 / Math.max(1, gpuFrames) + " us" : "off",
                gpuPassNanos[1] / 1000 / Math.max(1, gpuFrames), gpuPassNanos[2] / 1000 / Math.max(1, gpuFrames),
                quadsSubmitted / Math.max(1, timedFrames), quadsInView / Math.max(1, timedFrames),
                discardSections / Math.max(1, timedFrames), leftToGame / Math.max(1, timedFrames), timedFrames,
                String.format("%.1f", selector.detail()), maxFallbacks);
        maxFallbacks = 0;
        quadsInView = 0;
        quadsSubmitted = 0;
        discardSections = 0;
        leftToGame = 0;
        frameNanos = 0;
        timedFrames = 0;
        java.util.Arrays.fill(gpuPassNanos, 0);
        gpuFrames = 0;
        selections = 0;
        meshesUploaded = 0;
        meshesEvicted = 0;
    }

    private void switchWorld(LodWorld next) {
        if (world != null) {
            world.removeListener(listener);
        }
        generation.incrementAndGet();
        for (Resident r : resident.values()) {
            if (r.quadCount > 0) {
                arena.release(r.start, r.quadCount);
            }
        }
        resident.clear();
        requested.clear();
        stale.clear();
        provisional.clear();
        existence.clear();
        sectionCache.clear();
        drawList.clear();
        done.clear();
        rewritten.clear();
        colors.reset();
        tints.reset();
        freeSlotCount = 0;
        slotCount = 0;
        selectionDirty = true;
        world = next;
        previewLayer.attach(next);
        if (world != null) {
            world.addListener(listener);
            Farfield.LOG.info("LOD renderer attached to a LOD store");
        }
    }

    private void request(long key) {
        if (requested.size() >= MAX_IN_FLIGHT || !requested.add(key)) {
            return;
        }
        LodWorld w = world;
        int gen = generation.get();
        meshers.execute(() -> {
            MeshResult result;
            long start = System.nanoTime();
            try {
                LodSection section = PreviewLayer.isPreview(key) ? previewLayer.build(w, key) : readSection(w, key);
                result = section == null ? new MeshResult(gen, key, new long[0], NO_GROUPS, false, false, null, null)
                        : mesh(w, gen, key, section);
            } catch (IOException | RuntimeException e) {
                Farfield.LOG.debug("LOD mesh of {} failed", LodKey.toString(key), e);
                result = new MeshResult(gen, key, new long[0], NO_GROUPS, false, false, null, null);
            }
            meshNanos.addAndGet(System.nanoTime() - start);
            meshJobs.incrementAndGet();
            done.add(result);
        });
    }

    /** A stored section through {@link #sectionCache}, timed for the stat line; null when not stored. */
    private LodSection readSection(LodWorld w, long key) throws IOException {
        long start = System.nanoTime();
        LodSection section = sectionCache.read(w, key);
        meshReadNanos.addAndGet(System.nanoTime() - start);
        return section;
    }

    /** Meshes a section: opaque quads first, then translucent ones, each by direction. */
    private MeshResult mesh(LodWorld w, int gen, long key, LodSection section) throws IOException {
        MaterialClassifier materials = w.materials();
        // Partial and shaped blocks keep their shape on the finest level only;
        // coarser voxels hold more than the block and stay full. The draw level, not
        // the key's: a preview key carries an offset (a snow layer meshed as a cube
        // there hid the faces beside it, while the shader drew it 2/16 high).
        MaterialClassifier classes = drawLevel(key) == 0 ? materials : m -> {
            int c = materials.classOf(m);
            return c == MaterialClassifier.PARTIAL || c == MaterialClassifier.SHAPED ? MaterialClassifier.OPAQUE : c;
        };
        boolean translucent = hasTranslucent(section, classes);
        int[][] solid = translucent ? new int[6][] : null;
        byte[][] light = new byte[6][];
        if (PreviewLayer.isPreview(key)) {
            // Preview terrain is in full sky light; its neighbors are built from the tiles.
            solid = translucent ? previewLayer.solidNeighbors(w, key, classes, borderPlanes.get()) : null;
        } else {
            readNeighbors(w, key, classes, solid, light);
        }
        MeshBuffer out = meshBuffer.get();
        // Stored terrain reads missing chunks as air: no water walls into them.
        mesher.get().setEmptyColumnsUnknown(!PreviewLayer.isPreview(key));
        mesher.get().mesh(section, classes, tintedMaterials, null, solid, light, out);
        // Masked preview columns only hide faces: their own quads are dropped.
        boolean preview = PreviewLayer.isPreview(key);
        int dropped = preview ? PreviewLayer.maskGround(w) : -1;
        int droppedFluid = preview ? PreviewLayer.maskFluid(w) : -1;
        int[] groups = new int[GROUPS + 1];
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            long[] quads = out.quads(d);
            for (int i = 0, c = out.count(d); i < c; i++) {
                int m = Quad.material(quads[i]);
                if (m != dropped && m != droppedFluid) {
                    groups[group(classes, m, d) + 1]++;
                }
            }
        }
        for (int g = 0; g < GROUPS; g++) {
            groups[g + 1] += groups[g];
        }
        long[] all = new long[groups[GROUPS]];
        int[] next = java.util.Arrays.copyOf(groups, GROUPS);
        boolean cutout = false;
        boolean provisional = false;
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            long[] quads = out.quads(d);
            for (int i = 0, c = out.count(d); i < c; i++) {
                long q = quads[i];
                int m = Quad.material(q);
                if (m != dropped && m != droppedFluid) {
                    int g = group(classes, m, d);
                    all[next[g]++] = q;
                    if (g < Quad.DIRECTIONS) {
                        cutout |= colors.cutout(m);
                        provisional |= !colors.built(m);
                    }
                }
            }
        }
        int[] occupied = null;
        if (all.length > 0) {
            occupied = new int[OCCUPANCY_INTS];
            mesher.get().copyOpaqueRows(occupied);
        }
        // Every section's columns, even without quads: its neighbors blend with them.
        int[] columns = SurfaceBiomes.columns(section, classes, surfaceScratch.get());
        int[] packed = new int[BIOME_COLUMN_INTS];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = columns[2 * i] | columns[2 * i + 1] << 16;
        }
        return new MeshResult(gen, key, all, groups, cutout, provisional, occupied, packed);
    }

    private static int group(MaterialClassifier classes, int material, int direction) {
        return classes.classOf(material) == MaterialClassifier.TRANSLUCENT ? Quad.DIRECTIONS + direction : direction;
    }

    private static boolean hasTranslucent(LodSection section, MaterialClassifier classes) {
        for (int i = 0, n = section.paletteSize(); i < n; i++) {
            if (classes.classOf(section.paletteEntry(i)) == MaterialClassifier.TRANSLUCENT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the six same-level neighbors once: the light of their touching
     * layers into {@code light} (faces on a section border are lit like the
     * voxel in front of them, not in full sky light: underwater cliffs on a
     * border were bright), and their solid voxels into {@code solid} when
     * not null. Entries stay null for neighbors not stored, except the
     * horizontal ones of {@code solid}: there the edge of the known world
     * counts as solid, so water draws no wall toward chunks without data
     * (seen through the noise preview's water, or hanging over the void);
     * opaque ground keeps its wall. The neighbors' empty columns count as
     * solid the same way ({@link BinaryGreedyMesher#setEmptyColumnsUnknown}):
     * a section holds four chunks or more, some of them without data. A
     * neighbor written later meshes this section again ({@link #applyRewrites}).
     */
    private void readNeighbors(LodWorld w, long key, MaterialClassifier classes, int[][] solid, byte[][] light)
            throws IOException {
        int[][] planes = borderPlanes.get();
        byte[][] lights = borderLights.get();
        int level = LodKey.level(key);
        for (int d = 0; d < 6; d++) {
            int y = LodKey.y(key) + STEP[d][1];
            if (y < LodKey.Y_MIN || y > LodKey.Y_MAX) {
                continue;
            }
            long neighbor = LodKey.pack(level, LodKey.x(key) + STEP[d][0], y, LodKey.z(key) + STEP[d][2]);
            LodSection section = readSection(w, neighbor);
            if (section != null) {
                light[d] = BinaryGreedyMesher.borderLight(section, d, lights[d]);
                if (solid != null) {
                    solid[d] = BinaryGreedyMesher.borderPlane(section, d, classes, true, planes[d]);
                    BinaryGreedyMesher.addEmptyColumns(section, d, classes, solid[d]);
                }
            } else if (solid != null && STEP[d][1] == 0) {
                solid[d] = FULL_PLANE;
            }
        }
    }

    /** A neighbor layer solid everywhere (read only). */
    private static final int[] FULL_PLANE = filledPlane();

    private static int[] filledPlane() {
        int[] plane = new int[LodSection.SIZE];
        java.util.Arrays.fill(plane, -1);
        return plane;
    }

    /**
     * Sections the store wrote: they exist now (the selection must run again,
     * it may descend further), and resident ones are meshed again; the old
     * mesh is drawn until the new one arrives. A section whose mesh is being
     * built waits for that job, which may have read the old data.
     */
    private void applyRewrites() {
        Long key;
        while ((key = rewritten.poll()) != null) {
            if (existence.put(key.longValue(), EXISTS) != EXISTS) {
                selectionDirty = true;
            }
            if (resident.containsKey(key.longValue())) {
                stale.add(key.longValue());
            }
            previewLayer.realWritten(key);
            // Translucent faces of the neighbors were culled against the old data.
            long k = key;
            for (int d = 0; d < 6; d++) {
                int y = LodKey.y(k) + STEP[d][1];
                if (y < LodKey.Y_MIN || y > LodKey.Y_MAX) {
                    continue;
                }
                long neighbor = LodKey.pack(LodKey.level(k), LodKey.x(k) + STEP[d][0], y, LodKey.z(k) + STEP[d][2]);
                Resident r = resident.get(neighbor);
                if (r != null && r.quadCount > r.opaqueCount) {
                    stale.add(neighbor);
                }
            }
        }
        if (previewLayer.drainChanges(changedTiles)) {
            selectionDirty = true;
        }
        if (!changedTiles.isEmpty()) {
            LongIterator keys = resident.keySet().iterator();
            while (keys.hasNext()) {
                long k = keys.nextLong();
                if (PreviewLayer.isPreview(k) && changedTiles.contains(PreviewLayer.tileOf(k))) {
                    stale.add(k);
                }
            }
            changedTiles.clear();
        }
        LongIterator it = stale.iterator();
        while (it.hasNext() && requested.size() < MAX_IN_FLIGHT) {
            long k = it.nextLong();
            if (!resident.containsKey(k)) {
                it.remove();
            } else if (!requested.contains(k)) {
                it.remove();
                request(k);
            }
        }
    }

    private void uploadMeshes() {
        long budget = UPLOAD_BUDGET_QUADS;
        MeshResult m;
        while (budget > 0 && (m = done.poll()) != null) {
            requested.remove(m.key);
            if (m.generation != generation.get()) {
                continue;
            }
            Resident r = resident.get(m.key);
            if (r == null) {
                r = new Resident(allocateSlot());
                r.lastUsed = System.nanoTime();
                resident.put(m.key, r);
                selectionDirty = true; // a parent may now descend
            } else if (r.quadCount > 0) {
                arena.release(r.start, r.quadCount);
            }
            r.quadCount = m.quads.length;
            r.opaqueCount = m.groups[Quad.DIRECTIONS];
            r.groups = m.groups;
            r.cutout = m.cutout;
            if (m.provisional) {
                provisional.add(m.key);
            } else {
                provisional.remove(m.key);
            }
            r.occluders = m.occupancy != null;
            if (r.occluders) {
                long needed = (long) (r.slot + 1) * OCCUPANCY_INTS * 4;
                if (needed > occupancy.size()) {
                    occupancy.grow(Math.max(needed, occupancy.size() * 2), occupancy.size());
                }
                occupancy.upload((long) r.slot * OCCUPANCY_INTS * 4, m.occupancy);
            }
            if (m.biomeColumns != null) {
                long needed = (long) (r.slot + 1) * BIOME_COLUMN_INTS * 4;
                if (needed > biomeColumns.size()) {
                    biomeColumns.grow(Math.max(needed, biomeColumns.size() * 2), biomeColumns.size());
                }
                biomeColumns.upload((long) r.slot * BIOME_COLUMN_INTS * 4, m.biomeColumns);
            }
            meshesUploaded++;
            if (r.quadCount > 0) {
                long start = arena.allocate(r.quadCount);
                if (start < 0 && growArena(r.quadCount)) {
                    start = arena.allocate(r.quadCount);
                }
                if (start < 0) {
                    r.quadCount = 0; // out of GPU budget: drawn as empty until evictions free space
                    r.opaqueCount = 0;
                    r.groups = NO_GROUPS;
                } else {
                    r.start = start;
                    quads.upload(start * 8, m.quads, m.quads.length);
                    budget -= m.quads.length;
                }
            }
            writeSection(m.key, r);
            linkNeighbors(m.key, r);
        }
    }

    private boolean growArena(long needed) {
        long capacity = arena.capacity();
        long target = Math.max(capacity * 2, capacity + needed);
        if (target > MAX_QUADS) {
            return false;
        }
        quads.grow(target * 8, capacity * 8);
        arena.grow(target);
        Farfield.LOG.info("LOD quad buffer grown to {} MB", target * 8 >> 20);
        return true;
    }

    private int allocateSlot() {
        if (freeSlotCount > 0) {
            return freeSlots[--freeSlotCount];
        }
        int slot = slotCount++;
        long needed = (long) slotCount * SECTION_BYTES;
        if (needed > sectionInfo.size()) {
            sectionInfo.grow(sectionInfo.size() * 2, sectionInfo.size());
        }
        return slot;
    }

    private void releaseSlot(int slot) {
        if (freeSlots.length == freeSlotCount) {
            freeSlots = java.util.Arrays.copyOf(freeSlots, Math.max(64, freeSlotCount * 2));
        }
        freeSlots[freeSlotCount++] = slot;
    }

    private void writeSection(long key, Resident r) {
        int level = drawLevel(key);
        int size = LodSection.SIZE << level;
        sectionInfo.upload((long) r.slot * SECTION_BYTES, new int[]{
                LodKey.x(key) * size, LodKey.y(key) * size, LodKey.z(key) * size, level,
                (int) r.start, r.quadCount, r.occluders ? 1 : 0, 0});
    }

    /**
     * Writes a section's neighbor slots and its own slot into its resident
     * neighbors' tables (ambient occlusion across section borders); a
     * section without occluders is written as absent.
     */
    private void linkNeighbors(long key, Resident r) {
        long needed = (long) (r.slot + 1) * NEIGHBOR_INTS * 4;
        if (needed > neighborSlots.size()) {
            neighborSlots.grow(Math.max(needed, neighborSlots.size() * 2), neighborSlots.size());
        }
        int level = LodKey.level(key);
        int x = LodKey.x(key);
        int y = LodKey.y(key);
        int z = LodKey.z(key);
        for (int i = 0; i < NEIGHBOR_INTS; i++) {
            neighborScratch[i] = 0;
            if (i == 13) {
                continue;
            }
            int ny = y + i / 3 % 3 - 1;
            if (ny < LodKey.Y_MIN || ny > LodKey.Y_MAX) {
                continue;
            }
            Resident n = resident.get(LodKey.pack(level, x + i / 9 - 1, ny, z + i % 3 - 1));
            if (n == null) {
                continue;
            }
            if (n.occluders) {
                neighborScratch[i] = n.slot + 1;
            }
            oneInt[0] = r.occluders ? r.slot + 1 : 0;
            neighborSlots.upload(((long) n.slot * NEIGHBOR_INTS + (26 - i)) * 4, oneInt);
        }
        neighborSlots.upload((long) r.slot * NEIGHBOR_INTS * 4, neighborScratch);
    }

    /** Removes an evicted section from its resident neighbors' tables. */
    private void unlinkNeighbors(long key) {
        int level = LodKey.level(key);
        oneInt[0] = 0;
        for (int i = 0; i < NEIGHBOR_INTS; i++) {
            int ny = LodKey.y(key) + i / 3 % 3 - 1;
            if (i == 13 || ny < LodKey.Y_MIN || ny > LodKey.Y_MAX) {
                continue;
            }
            Resident n = resident.get(LodKey.pack(level, LodKey.x(key) + i / 9 - 1, ny, LodKey.z(key) + i % 3 - 1));
            if (n != null) {
                neighborSlots.upload(((long) n.slot * NEIGHBOR_INTS + (26 - i)) * 4, oneInt);
            }
        }
    }

    private void evictUnused(long now) {
        evicted.clear();
        ObjectIterator<Long2ObjectOpenHashMap.Entry<Resident>> it = resident.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectOpenHashMap.Entry<Resident> e = it.next();
            Resident r = e.getValue();
            if (now - r.lastUsed > EVICT_AFTER_NANOS) {
                evicted.add(e.getLongKey());
                if (r.quadCount > 0) {
                    arena.release(r.start, r.quadCount);
                }
                releaseSlot(r.slot);
                meshesEvicted++;
                it.remove();
                selectionDirty = true;
            }
        }
        for (int i = 0, n = evicted.size(); i < n; i++) {
            unlinkNeighbors(evicted.getLong(i));
        }
    }

    /**
     * Builds and uploads the colors of new block states and the biome tints.
     * A resource reload or another level builds everything again, and meshes
     * again what is resident (which materials depend on the biome may change).
     */
    private void uploadColors(Minecraft mc, ClientLevel level) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        if (missing != missingModel || level != colorLevel) {
            if (missingModel != null) {
                stale.addAll(resident.keySet());
            }
            missingModel = missing;
            colorLevel = level;
            colors.reset();
            tints.reset();
        }
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Biome plains = biomes.get(Biomes.PLAINS);
        if (plains == null) {
            plains = biomes.stream().findFirst().orElse(null);
            if (plains == null) {
                return;
            }
        }
        int first = colors.update(world.blockStates(), world.materials(), tints, plains);
        if (!provisional.isEmpty() && colors.count() == world.blockStates().size()) {
            stale.addAll(provisional);
            provisional.clear();
        }
        if (first >= 0) {
            int stride = MaterialColors.STRIDE;
            long needed = (long) colors.count() * stride * 4;
            if (needed > colorBuffer.size()) {
                colorBuffer.grow(Math.max(needed, colorBuffer.size() * 2), (long) first * stride * 4);
            }
            colorBuffer.upload((long) first * stride * 4,
                    java.util.Arrays.copyOfRange(colors.colors(), first * stride, colors.count() * stride));
        }
        atlas = faceTextures.upload();
        if (tints.update(world.biomes(), biomes, plains)) {
            long needed = (long) tints.length() * 4;
            if (needed > biomeBuffer.size()) {
                biomeBuffer.grow(Math.max(needed, biomeBuffer.size() * 2), 0);
            }
            biomeBuffer.upload(0, java.util.Arrays.copyOf(tints.table(), tints.length()));
        }
    }

    private void updateFrustum(Matrix4f viewRotation, Matrix4f projection) {
        float m11 = Math.abs(projection.m11());
        float m00 = Math.abs(projection.m00());
        float fovY = (float) (2 * Math.atan(1 / m11)) * 1.1f;
        cullMatrix.setPerspective(fovY, m11 / m00, 1f, (float) MAX_DISTANCE * 2).mul(viewRotation);
        frustum.set(cullMatrix);
    }

    /** The face directions of a section that can face the camera ({@link FaceDirections}), 0 outside the frustum. */
    private int visibleFaces(long key, Vec3 cam) {
        int size = LodSection.SIZE << drawLevel(key);
        float x = (float) (LodKey.x(key) * (double) size - cam.x);
        float y = (float) (LodKey.y(key) * (double) size - cam.y);
        float z = (float) (LodKey.z(key) * (double) size - cam.z);
        if (!frustum.testAab(x, y, z, x + size, y + size, z + size)) {
            return 0;
        }
        return FaceDirections.front(x, y, z, size);
    }

    /**
     * Whether a section overlaps the grid of sections the game may draw: only
     * there can its fragments give way to the game's terrain (lod.frag).
     */
    /**
     * Whether the game draws every chunk section a LOD section covers, by
     * the rule of {@code vanillaDraws} in {@code lod.frag}: every fragment of
     * the section would be discarded, so it is not drawn at all (ADR-047).
     * Levels 0 and 1 only (8 and 64 chunk sections); the Sodium distance
     * keeps a block of margin against float rounding, so a section is never
     * skipped where the shader would keep a fragment.
     */
    private boolean gameDrawsAll(long key, Vec3 cam, int renderDistance) {
        int level = drawLevel(key);
        if (level > 1 || gridWidth <= 0) {
            return false;
        }
        int chunks = 2 << level;
        int sx0 = LodKey.x(key) * chunks;
        int sy0 = LodKey.y(key) * chunks;
        int sz0 = LodKey.z(key) * chunks;
        int camX = (int) Math.floor(cam.x);
        int camY = (int) Math.floor(cam.y);
        int camZ = (int) Math.floor(cam.z);
        for (int sy = sy0; sy < sy0 + chunks; sy++) {
            for (int sz = sz0; sz < sz0 + chunks; sz++) {
                for (int sx = sx0; sx < sx0 + chunks; sx++) {
                    int gx = sx - gridMinX;
                    int gy = sy - gridMinY;
                    int gz = sz - gridMinZ;
                    if (gx < 0 || gy < 0 || gz < 0 || gx >= gridWidth || gz >= gridWidth || gy >= gridHeight) {
                        return false;
                    }
                    int bit = (gz * gridWidth + gx) * gridHeight + gy;
                    if ((drawnBits[bit >> 5] >>> (bit & 31) & 1) == 0) {
                        return false;
                    }
                    if (sodiumRule) {
                        double d = renderDistance * 16 - 1;
                        double nx = nearest(sx * 16 - camX, cam.x - camX);
                        double ny = nearest(sy * 16 - camY, cam.y - camY);
                        double nz = nearest(sz * 16 - camZ, cam.z - camZ);
                        if (nx * nx + nz * nz >= d * d || Math.abs(ny) >= d) {
                            return false;
                        }
                    } else {
                        if (Math.abs(sy - (camY >> 4)) > renderDistance) {
                            return false;
                        }
                        int cx = Math.max(0, Math.abs(sx - (camX >> 4)) - 1);
                        int cz = Math.max(0, Math.abs(sz - (camZ >> 4)) - 1);
                        int far = Math.max(cx, cz);
                        int near = Math.min(cx, cz);
                        if (near * near + far * far >= renderDistance * renderDistance) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** Sodium's nearest point of a section grown by one block, on one axis, relative to the camera. */
    private static double nearest(int offset, double cameraFrac) {
        int lo = offset - 1;
        int hi = offset + 17;
        return (lo > 0 ? lo : hi < 0 ? hi : 0) - cameraFrac;
    }

    private boolean nearGame(long key) {
        int size = LodSection.SIZE << drawLevel(key);
        long x = LodKey.x(key) * (long) size;
        long y = LodKey.y(key) * (long) size;
        long z = LodKey.z(key) * (long) size;
        return x < (gridMinX + gridWidth) * 16L && x + size > gridMinX * 16L
                && y < (gridMinY + gridHeight) * 16L && y + size > gridMinY * 16L
                && z < (gridMinZ + gridWidth) * 16L && z + size > gridMinZ * 16L;
    }

    /**
     * Writes the draw commands of a section into a list: its groups of that
     * pass whose direction is in {@code faces}, adjacent groups in one command.
     */
    private void addCommands(Resident r, int list, int faces) {
        int first = list == TRANSLUCENT ? Quad.DIRECTIONS : 0;
        int runStart = r.groups[first];
        int runEnd = runStart;
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            int start = r.groups[first + d];
            int end = r.groups[first + d + 1];
            if ((faces & 1 << d) == 0 || start == end) {
                continue;
            }
            if (start != runEnd) {
                addCommand(list, runStart, runEnd, r.slot);
                runStart = start;
            }
            runEnd = end;
        }
        addCommand(list, runStart, runEnd, r.slot);
    }

    private void addCommand(int list, int start, int end, int slot) {
        if (end == start) {
            return;
        }
        while (end - start > INDEXED_QUADS) {
            addCommand(list, start, start + INDEXED_QUADS, slot);
            start += INDEXED_QUADS;
        }
        IntBuffer out = commands[list];
        if (out.remaining() < COMMAND_INTS) {
            out = MemoryUtil.memRealloc(out, out.capacity() * 2);
            commands[list] = out;
        }
        // gl_VertexID is the index plus the base vertex: the shader reads the quads from the group's start.
        out.put((end - start) * 6).put(1).put(0).put(start * 4).put(slot);
        quadsSubmitted += end - start;
        draws[list]++;
    }

    /**
     * Vanilla's lightmap texture (updated by the game renderer before the
     * level renders): binding it as the light layer stores its id in shader
     * texture slot 2, as the terrain shaders expect.
     */
    private static int lightmap(Minecraft mc) {
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        return RenderSystem.getShaderTexture(2);
    }

    /** Reads the GPU timestamps of earlier frames that are ready, without waiting. */
    private void readGpuTimers() {
        for (int slot = 0; slot < TIMER_SLOTS; slot++) {
            int base = slot * TIMESTAMPS;
            if (!timerPending[slot]
                    || glGetQueryObjecti(timerQueries[base + TIMESTAMPS - 1], GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) {
                continue;
            }
            long previous = glGetQueryObjectui64(timerQueries[base], GL_QUERY_RESULT);
            for (int i = 1; i < TIMESTAMPS; i++) {
                long t = glGetQueryObjectui64(timerQueries[base + i], GL_QUERY_RESULT);
                gpuPassNanos[i - 1] += t - previous;
                previous = t;
            }
            gpuFrames++;
            timerPending[slot] = false;
        }
    }

    /** Binds a program variant and sets its uniforms for this frame ({@link #viewProj} set). */
    private void use(Shader s, Vec3 cam, Minecraft mc, boolean translucent) {
        int blockX = (int) Math.floor(cam.x);
        int blockY = (int) Math.floor(cam.y);
        int blockZ = (int) Math.floor(cam.z);
        float[] fogColor = RenderSystem.getShaderFogColor();
        GlStateManager._glUseProgram(s.program.id());
        glUniformMatrix4fv(s.viewProj, false, viewProj.get(matrix));
        glUniform3i(s.cameraBlock, blockX, blockY, blockZ);
        glUniform3f(s.cameraFrac, (float) (cam.x - blockX), (float) (cam.y - blockY), (float) (cam.z - blockZ));
        glUniform2f(s.depthRange, 8f, (float) MAX_DISTANCE * 2);
        glUniform1i(s.renderDistance, mc.options.getEffectiveRenderDistance());
        glUniform3i(s.gridMin, gridMinX, gridMinY, gridMinZ);
        glUniform2i(s.gridSize, gridWidth, gridHeight);
        glUniform1i(s.sodiumRule, sodiumRule ? 1 : 0);
        glUniform1i(s.lightmap, LIGHTMAP_UNIT);
        glUniform4f(s.fogColor, fogColor[0], fogColor[1], fogColor[2], 1f);
        if (pushedFog || followVanillaFog) {
            // The game's terrain fog, set up just before the LODs draw (moved out by
            // the fog mixin, or kept); its shape only when kept, as the LOD fog was a sphere.
            glUniform2f(s.fog, RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            glUniform1i(s.fogCylinder, followVanillaFog && RenderSystem.getShaderFogShape() == FogShape.CYLINDER ? 1 : 0);
        } else {
            // The fog mixin did not run or left a mod's fog alone: the LODs' own far fog.
            glUniform2f(s.fog, FOG_START, FOG_END);
            glUniform1i(s.fogCylinder, 0);
        }
        glUniform1i(s.translucent, translucent ? 1 : 0);
        glUniform1i(s.debugLevels, RenderSettings.debugLevels() ? 1 : 0);
        // Vanilla's Biome Blend option (0 off, else the radius in blocks), as the game's terrain.
        glUniform1i(s.biomeBlend, mc.options.biomeBlendRadius().get());
        glUniform1i(s.atlas, ATLAS_UNIT);
        glUniform2f(s.atlasSize, faceTextures.width(), Math.max(1, faceTextures.height()));
        int textureDistance = RenderSettings.textureDistance();
        glUniform1f(s.textureEnd, atlas == 0 ? 0
                : textureDistance == RenderSettings.ALL_DISTANCES ? Float.MAX_VALUE : textureDistance);
    }

    private void draw(Vec3 cam, Matrix4f viewRotation, Matrix4f projection, Minecraft mc) {
        int solidDraws = draws[SOLID];
        int discardDraws = draws[DISCARD];
        int translucentDraws = draws[TRANSLUCENT];
        long opaqueBytes = (long) (solidDraws + discardDraws) * COMMAND_BYTES;
        long bytes = opaqueBytes + (long) translucentDraws * COMMAND_BYTES;
        if (bytes > indirect.size()) {
            indirect.grow(Math.max(bytes, indirect.size() * 2), 0);
        }
        long offset = 0;
        for (IntBuffer list : commands) {
            list.flip();
            indirect.upload(offset, list);
            offset += (long) list.remaining() * 4;
        }

        viewProj.set(projection).mul(viewRotation);

        // Timestamps rather than a time-elapsed query: the game may have one open.
        int slot = timerSlot;
        boolean timed = !timerPending[slot];
        if (timed) {
            glQueryCounter(timerQueries[slot * TIMESTAMPS], GL_TIMESTAMP);
        }

        int previousUnit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL_TEXTURE0 + ATLAS_UNIT);
        GlStateManager._bindTexture(atlas);
        GlStateManager._activeTexture(GL_TEXTURE0 + LIGHTMAP_UNIT);
        GlStateManager._bindTexture(lightmap(mc));
        GlStateManager._activeTexture(previousUnit);

        GlStateManager._glBindVertexArray(vao);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, quads.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, sectionInfo.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, colorBuffer.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, biomeBuffer.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, occupancy.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, neighborSlots.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, drawnSections.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, biomeColumns.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect.id());
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull(); // lod.vert winds every face counter-clockwise from outside

        // Depth pre-pass: the shaded passes then run the full fragment shader
        // (ambient occlusion, biome blending, textures) once per pixel instead
        // of once per overlapping face; its discards would otherwise switch
        // off the early depth test. Sections that cannot discard go first,
        // with a shader without discard, so the pre-pass keeps early tests
        // for them and the others mostly land behind depth already written.
        boolean prepass = RenderSettings.depthPrepass();
        Shader shaded = prepass ? earlyZ : plain;
        int opaqueDraws = solidDraws + discardDraws;
        if (prepass && opaqueDraws > 0) {
            RenderSystem.colorMask(false, false, false, false);
            if (solidDraws > 0) {
                use(depthSolid, cam, mc, false);
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0, solidDraws, 0);
            }
            if (discardDraws > 0) {
                use(depthOnly, cam, mc, false);
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, (long) solidDraws * COMMAND_BYTES,
                        discardDraws, 0);
            }
            RenderSystem.colorMask(true, true, true, true);
        }
        if (timed) {
            glQueryCounter(timerQueries[slot * TIMESTAMPS + 1], GL_TIMESTAMP);
        }
        if (opaqueDraws > 0) {
            use(shaded, cam, mc, false);
            if (prepass) {
                RenderSystem.depthFunc(GL_EQUAL);
                RenderSystem.depthMask(false);
            }
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0, opaqueDraws, 0);
            RenderSystem.depthFunc(GL_LEQUAL);
            RenderSystem.depthMask(true);
        }
        if (timed) {
            glQueryCounter(timerQueries[slot * TIMESTAMPS + 2], GL_TIMESTAMP);
        }
        if (translucentDraws > 0) {
            use(shaded, cam, mc, true);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.depthMask(false);
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, opaqueBytes, translucentDraws, 0);
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }

        if (timed) {
            glQueryCounter(timerQueries[slot * TIMESTAMPS + 3], GL_TIMESTAMP);
            timerPending[slot] = true;
            timerSlot = (slot + 1) % TIMER_SLOTS;
        }
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        GlStateManager._glUseProgram(0);
        // Vanilla terrain draws next with its own depth range: start it clean.
        RenderSystem.clear(GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }
}
