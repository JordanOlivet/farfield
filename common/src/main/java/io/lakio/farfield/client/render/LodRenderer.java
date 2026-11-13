package io.lakio.farfield.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.LodWorldProvider;
import io.lakio.farfield.client.StreamClient;
import io.lakio.farfield.core.storage.LooksCache;
import io.lakio.farfield.platform.Services;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.BinaryGreedyMesher;
import io.lakio.farfield.core.mesh.BiomeBlend;
import io.lakio.farfield.core.mesh.FaceDirections;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.MeshBuffer;
import io.lakio.farfield.core.mesh.Quad;
import io.lakio.farfield.core.mesh.SurfaceBiomes;
import io.lakio.farfield.core.preview.PreviewStore;
import io.lakio.farfield.core.preview.PreviewTile;
import io.lakio.farfield.core.select.LodSelector;
import io.lakio.farfield.core.select.WantOrder;
import io.lakio.farfield.core.util.RangeAllocator;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.RenderSettings;
import io.lakio.farfield.mixin.client.LevelRendererAccessor;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
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
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

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
 *       demand, compacted down once mostly empty), one 32-byte record per resident section, baked face
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

    /**
     * Seconds after a join or a teleport at which the fill line samples the
     * sections missing in view. Before {@link #INSTANCE}: an instance field
     * sizes its array from it.
     */
    private static final int[] FILL_CHECKS = {0, 1, 2, 5, 10, 20, 30, 45, 60};
    private static final LodRenderer INSTANCE = new LodRenderer();

    /** The farthest LODs drawn (E.3b): the fog distance's maximum, the preview's coarsest tiles. */
    private static final double MAX_DISTANCE = RenderConfig.MAX_FOG_DISTANCE;
    /** How far {@code debug here} follows the view. */
    private static final int LOOK_DISTANCE = 4096;
    /** Mesh jobs in flight: 32 to 128 filled the view as fast (ADR-056). */
    private static final int MAX_IN_FLIGHT = 64;
    private static final long UPLOAD_BUDGET_QUADS = 1 << 20;
    /**
     * Time a frame may spend uploading meshes, the first always going in
     * (ADR-144): with only the quad budget, 64 to 112 meshes of the first
     * view went in one frame, 41 to 116 ms.
     */
    private static final long UPLOAD_BUDGET_NANOS = 3_000_000L;
    private static final long INITIAL_QUADS = 8L << 20;
    /** The quad buffer's size reached in the last session, its start in this one (ADR-144). */
    private static final String QUADS_FILE = "farfield-quad-buffer.txt";
    /** The quad buffer's start, and its floor when compacted for the first minute (the first view loading). */
    private long startQuads = INITIAL_QUADS;
    private long startedNanos;
    /** Section slots the last session reached, a power of two (ADR-144); kept as this one passes them. */
    private static int rememberedSlots;
    private static final long MAX_QUADS = 128L << 20;
    /** The quad buffer is compacted down after staying at most a quarter used this long (a spyglass zoom grows it). */
    private static final long SHRINK_AFTER_NANOS = 10_000_000_000L;
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
    /** Per section slot: blended tints per column and kind (BiomeBlend), then the radius + 1 they were made with. */
    private static final int BLEND_SLOT_INTS = BiomeBlend.COLUMNS * BiomeTints.KINDS + 1;
    /** Render-thread time per frame for blending sections again (neighbors arrived, radius or tints changed). */
    private static final long BLEND_BUDGET_NANOS = 1_000_000L;
    /** A resident mesh the selection has not used for this long is freed. */
    private static final long EVICT_AFTER_NANOS = 10_000_000_000L;
    /** The selection runs again when the camera moved this far (squared blocks)... */
    private static final double RESELECT_DISTANCE_SQ = 8 * 8;
    /**
     * The largest far / near ratio of the LODs' depth range (ADR-140): the
     * near plane moves out past 8 blocks only for draw distances past about
     * 4 million blocks.
     */
    private static final float DEPTH_RATIO = 1 << 20;
    /**
     * The LOD distance under a shader pack (ADR-141): Photon rebuilds a
     * combined depth from the game's near plane (0.05) to the LODs' extent;
     * in float it reaches 1 past about 800,000 blocks, and its frames came
     * out black from 1,048,576 blocks (fine at 262,144, Fabric pack, 4K).
     */
    private static final int PACK_MAX_DISTANCE = 1 << 18;
    private boolean packCapLogged;
    /** ...or after this long anyway... */
    private static final long RESELECT_NANOS = 1_000_000_000L;
    /** ...but never sooner than this after the last one (ADR-140)... */
    private static final long MIN_RESELECT_NANOS = 40_000_000L;
    /**
     * ...nor than this many times the last one's own time (ADR-143): a
     * selection keeps at most about a fifth of the render thread, however far
     * it reaches.
     */
    private static final long SELECTION_SHARE = 5;
    /** The last selection's time on the render thread. */
    private long lastSelectNanos;
    /** Sections freed by one eviction call; the rest go on the next frames (ADR-143). */
    private static final int EVICT_PER_FRAME = 512;
    private boolean evictBacklog;
    /** ...or when the view turned by more than about 25 degrees: the wanted meshes go in view order. */
    private static final double RESELECT_TURN_COS = 0.9;
    /** A camera jump this far between two frames (squared blocks) counts as a teleport for the fill line. */
    private static final double TELEPORT_DISTANCE_SQ = 256 * 256;
    /** How long after a join or a teleport the fill line waits at most before it is logged... */
    private static final long FILL_WINDOW_NANOS = 60_000_000_000L;
    /** ...and how long nothing may be missing in view, with no mesh in flight, to log it sooner. */
    private static final long FILL_SETTLED_NANOS = 5_000_000_000L;
    /** Time between two stat lines. */
    private static final long STATS_INTERVAL_NANOS = 10_000_000_000L;
    /** Time per frame for building material looks ({@link MaterialColors})... */
    private static final long LOOKS_BUDGET_NANOS = 3_000_000L;
    /** ...up to what keeps the frame at 60 fps when more than this many wait (ADR-062). */
    private static final int LOOKS_BACKLOG = 64;
    private static final long LOOKS_FRAME_NANOS = 1_000_000_000L / 60;
    private static final byte EXISTS = 1;
    private static final byte ABSENT = 2;
    private static final int TIMER_SLOTS = 4;
    /** GPU timestamps per frame: start, then after the pre-pass, opaque, plant and translucent passes. */
    private static final int TIMESTAMPS = 5;
    private static final int[][] STEP = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    /**
     * Quad groups of a section, in range order: opaque quads by direction,
     * then translucent ones by direction, so a pass draws only the directions
     * that can face the camera ({@link FaceDirections}); then the plants'
     * diagonals ({@link #PLANT_GROUP}), drawn whatever the direction.
     */
    private static final int GROUPS = 2 * Quad.DIRECTIONS + 1;
    /** The group of the plants' diagonals: opaque with holes, turned toward the camera by the shader. */
    private static final int PLANT_GROUP = 2 * Quad.DIRECTIONS;
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
    /** ...translucent quads... */
    private static final int TRANSLUCENT = 2;
    /**
     * ...and the plants' diagonals, drawn once after the opaque pass, outside
     * the depth pre-pass, with their holes cut out (ADR-059).
     */
    private static final int PLANTS = 3;

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
    /**
     * The plants' pass: holes cut before any shading, the tint once per vertex.
     * Its own program, so the other passes keep a shader without that code
     * (in theirs it cost the opaque pass 126 us).
     */
    private Shader plants;
    /**
     * The LOD passes under a shader pack (ADR-113), null without Iris; the
     * shaded and plant variants linked with the pack's code, for
     * {@link #packGeneration}.
     */
    private ShaderPackLink packLink;
    private int packGeneration = -1;
    private Shader packShaded;
    private Shader packPlain;
    private Shader packPlants;
    private Shader packTranslucent;
    /** The pre-pass under a pack with a TAA jitter: its depth must match the jittered shaded pass. */
    private Shader packDepthSolid;
    private Shader packDepthOnly;
    /** The pack generation whose TAA jitter did not build: its programs go without it. */
    private int packNoTaa = -1;
    /**
     * Under a pack's DH contract (ADR-119): its dh_terrain and dh_water linked
     * with the bridge (dh_bridge.vert, dh_bridge.frag), for {@link #dhGeneration}.
     */
    private Shader dhOpaque;
    /** A contract was forced before the pack link existed: load the pack again once it does. */
    private boolean pendingPackReload;
    private Shader dhTranslucent;
    private int dhGeneration = -1;
    /** Per material, the shader pack's block id (Voxy's customId), for the pack's snippets. */
    private GlBuffer packIds;
    private int packIdsCount = -1;
    private int packIdsGeneration = -1;
    private int[] packIdsScratch = new int[0];
    /** The translucent LODs of this frame wait for the game's translucent stage (under a pack). */
    private boolean translucentPending;
    /** Their program, and whether they wait for the pack's deferred passes to have run (DH's water). */
    private Shader translucentShader;
    private boolean translucentAfterDeferred;
    private Vec3 translucentCam;
    private long translucentOffset;
    private int translucentCount;
    private GlBuffer quads;
    /** Since when the quad buffer has been at most a quarter used, -1 when it is not. */
    private long arenaLowSince = -1;
    private GlBuffer sectionInfo;
    private GlBuffer colorBuffer;
    private GlBuffer biomeBuffer;
    /** Opaque voxels per section slot ({@link #OCCUPANCY_INTS} ints), for ambient occlusion. */
    private GlBuffer occupancy;
    /** Per section slot, the slots of its resident same-level neighbors ({@link #NEIGHBOR_INTS}). */
    private GlBuffer neighborSlots;
    /** Per section slot, its columns' surface biomes ({@link #BIOME_COLUMN_INTS}), for biome blending. */
    private GlBuffer biomeColumns;
    /**
     * Per section slot, its columns' tints blended once ({@link #BLEND_SLOT_INTS}, binding 10):
     * the shader reads 4 of them per fragment instead of blending up to 25 columns.
     */
    private GlBuffer blendedTints;
    /** Sections to blend again within {@link #BLEND_BUDGET_NANOS}: a neighbor arrived, the radius or the tints changed. */
    private final LongOpenHashSet blendDirty = new LongOpenHashSet();
    private final int[][] blendSections = new int[9][];
    private final int[] blendScratch = new int[BLEND_SLOT_INTS];
    /** Vanilla's Biome Blend the blended tints follow; -1 before the first frame. */
    private int blendRadius = -1;
    private final int[] neighborScratch = new int[NEIGHBOR_INTS];
    private final int[] oneInt = new int[1];
    /**
     * Per slot (ADR-148): the resident section's key and the last time the
     * selection used it (drawn, or relied on to descend), {@link Long#MAX_VALUE}
     * for a free slot. The evictions scan these arrays in order: walking the
     * resident map read every section's object, 1 to 1.5 ms each 60 frames.
     */
    private long[] slotKeys = new long[1024];
    private long[] slotLastUsed = new long[1024];
    /**
     * Single ints written into the neighbor tables this frame, by int index,
     * the last write kept: one compute pass writes them all before the draws
     * (ADR-148). One buffer call per int cost 8 us a mesh and 5 to 10 ms for
     * 512 evictions.
     */
    private final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap neighborWrites =
            new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
    private java.nio.IntBuffer scatterScratch = org.lwjgl.BufferUtils.createIntBuffer(4096);
    private GlProgram scatterProgram;
    private int scatterCountU;
    private GlBuffer scatterPairs;
    private final LongArrayList evicted = new LongArrayList();
    /**
     * Sections the terrain renderer has compiled around the camera, one bit
     * each ({@link #updateDrawnSections}): LODs give way only where the game
     * really draws, so chunks not built yet (the outer ring waiting for its
     * neighbors, everything right after a teleport) keep their LODs.
     */
    private GlBuffer drawnSections;
    /** Chunk columns whose preview fragments are discarded ({@link #handOver}), around the camera. */
    private GlBuffer previewHidden;
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
    /**
     * GPU culling of the far sections (ADR-146): a compute pass does the
     * command loop's frustum and face tests and writes their draw commands.
     * Per slot, its groups and flags ({@link #CULL_INTS} ints, written with
     * the mesh); per selection, the slots to cull; per frame, three command
     * lists (solid, cutout, plants) and their counts.
     */
    private GlProgram cullProgram;
    private int cullCountU;
    private int cullMaxU;
    private int cullPlanesU;
    private int cullCameraBlockU;
    private int cullCameraFracU;
    private int cullPlantsU;
    private GlBuffer cullGroups;
    private GlBuffer cullCandidates;
    private GlBuffer gpuCommands;
    private GlBuffer gpuCounts;
    /** The slots the last selection gave the GPU, and the most commands a list of theirs may hold. */
    private int gpuCandidates;
    private int gpuMaxCommands;
    private it.unimi.dsi.fastutil.ints.IntArrayList gpuSlots = new it.unimi.dsi.fastutil.ints.IntArrayList();
    /** The selection's sections the CPU loop still draws: near the game's terrain, or not eligible. */
    private LongArrayList cpuList = new LongArrayList();
    /** GPU-culled sections with translucent faces: those stay sorted on the CPU, far to near. */
    private LongArrayList gpuTranslucent = new LongArrayList();
    /** The same lists of the selection being applied (ADR-148), swapped in when it is whole. */
    private it.unimi.dsi.fastutil.ints.IntArrayList nextGpuSlots = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private LongArrayList nextCpuList = new LongArrayList();
    private LongArrayList nextGpuTranslucent = new LongArrayList();
    /** Translucent sections of this frame, the translucent order's indexes point here. */
    private final LongArrayList frameTranslucent = new LongArrayList();
    /** Slots freed since the last selection: reused only after it, so no culled slot draws another section. */
    private final it.unimi.dsi.fastutil.ints.IntArrayList pendingFreeSlots = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private static final int CULL_INTS = 16;
    private static final int[] NO_CULL_GROUPS = new int[CULL_INTS];
    private final org.joml.Vector4f plane = new org.joml.Vector4f();
    private final float[] planes = new float[24];
    /** The shared index buffer of {@link #INDEXED_QUADS} quads. */
    private GlBuffer quadIndices;
    private int vao;
    private RangeAllocator arena;
    /** Texture unit of the face atlas; the game tracks units 0 to 11 and uses the low ones. */
    private static final int ATLAS_UNIT = 10;
    /** Texture unit of vanilla's lightmap for the LOD shader. */
    private static final int LIGHTMAP_UNIT = 11;
    /** Texture unit of the game's depth under a shader pack, outside the game's tracked units. */
    private static final int GAME_DEPTH_UNIT = 12;

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
    /**
     * Fog distance meaning no fog: beyond anything drawn, the LODs' farthest
     * extent included (ADR-143). It was 10 million blocks: past 16 million
     * drawn, the hidden fog began nearer than the shown one (60 % of the
     * distance), and hiding the fog showed more of it.
     */
    private static final float NO_FOG = (float) (MAX_DISTANCE * 8);
    private double lastSelectionDistance = -1;
    /**
     * The farthest drawn LOD geometry from the camera at the last selection,
     * in blocks (square sections cross the selection's radius): the packs'
     * LOD distance and the depth range follow it (ADR-140).
     */
    private double drawnExtent = RenderConfig.DEFAULT_DRAW_DISTANCE;
    /**
     * The nearest drawn LOD geometry from the camera at the last selection,
     * in blocks, less the reselection step: the depth range's near plane
     * moves out to half of it (ADR-141).
     */
    private double drawnNearest;
    /** The LODs' depth range of this frame, set at {@link #beginLevel} (ADR-140). */
    private float depthNear = 8f;
    private float depthFar = 2f * RenderConfig.DEFAULT_DRAW_DISTANCE;
    /** Per slot: before the passes, after the depth pre-pass, after the opaque pass, at the end. */
    private final int[] timerQueries = new int[TIMER_SLOTS * TIMESTAMPS];
    private final boolean[] timerPending = new boolean[TIMER_SLOTS];
    private int timerSlot;
    /**
     * Per slot: before and after the translucent LODs under a shader pack,
     * drawn later in the frame (Iris's translucent stage), so outside the
     * span of {@link #timerQueries}; added to the translucent pass's time.
     */
    private final int[] packTranslucentQueries = new int[TIMER_SLOTS * 2];
    private final boolean[] packTranslucentPending = new boolean[TIMER_SLOTS];
    private int packTranslucentSlot;

    private LodWorld world;
    /** On a server with Farfield, the streaming that fills {@link #world} (ADR-068); else null. */
    private StreamClient stream;
    private int streamGeneration;
    private final AtomicInteger generation = new AtomicInteger();
    private final LodSelector selector = new LodSelector(LodWorld.LEVELS, 4.0);
    private final Long2ObjectOpenHashMap<Resident> resident = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet requested = new LongOpenHashSet();
    private final LongOpenHashSet stale = new LongOpenHashSet();
    /** Sections written while their first mesh was being built: meshed again once it lands. */
    private final LongOpenHashSet rewrittenWhileMeshing = new LongOpenHashSet();
    /** Wanted sections skipped while the mirror stores their data; selected again once it is stored. */
    private final LongOpenHashSet waitingStore = new LongOpenHashSet();
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
    /**
     * Mesh jobs' processor time and their wait between request and start:
     * a job far longer than its processor time ran on a starved thread
     * (ADR-078, seen at 23 ms a job while a pack joins its world).
     */
    private final java.util.concurrent.atomic.AtomicLong meshCpuNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong meshWaitNanos = new java.util.concurrent.atomic.AtomicLong();
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
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
    /** Sections with translucent quads this frame: distance bits << 32 | draw list index << 6 | faces. */
    private final LongArrayList translucentOrder = new LongArrayList();
    private final LongConsumer drawSink = drawList::add;
    /** Sections whose mesh the last selection needs, in traversal order. */
    private final LongArrayList wanted = new LongArrayList();
    private final LongConsumer wantSink = wanted::add;
    /** The wanted and stale sections of the last selection, nearest and in view first (ADR-056). */
    private final WantOrder wantOrder = new WantOrder();
    /** The next entry of {@link #wantOrder} to request. */
    private int wantCursor;
    private boolean selectionDirty = true;
    /** The noise preview setting the last selection ran with. */
    private boolean selectedPreview;
    /** The preview light setting the resident preview meshes were built with (ADR-117). */
    private boolean meshedPreviewLight = RenderSettings.previewLight();
    /** The kinds of features the resident preview meshes were built with (plan item D.3). */
    private int meshedPreviewFeatures = RenderSettings.previewFeatures();
    private final PreviewLayer previewLayer = new PreviewLayer(meshers);
    /** Preview tiles changed since the last frame, with the chunks whose sections must be meshed again. */
    private final Map<Long, int[]> changedTiles = new java.util.HashMap<>();
    /** Most sections a selection drew coarser than wanted since the last stat line. */
    private int maxFallbacks;
    /** A few of those sections since the last stat line, with why (ADR-093). */
    private final java.util.List<String> fallbackReasons = new java.util.ArrayList<>();
    private static final int FALLBACK_REASONS = 6;
    /** Most sections a selection drew from a copy older than the server's since the last stat line. */
    private int maxOutdatedDrawn;
    /** Most sections a selection drew as their children, waiting for their own mesh. */
    private int maxFinerFallbacks;
    /**
     * The hole detector (ADR-077): chunk columns near the camera, beyond the
     * game's own render distance, where no drawn section has a quad, and
     * since when; scanned every {@link #HOLE_SCAN_NANOS}.
     */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap holeSince =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private static final long HOLE_SCAN_NANOS = 250_000_000L;
    /** For the agent inbox (ADR-095): sections the last frame drew, sections wanted in view by the last selection. */
    private int lastDrawn;
    private int lastWantedInView;
    private static final int HOLE_RADIUS_CHUNKS = 32;
    private static final int HOLE_EXAMPLES = 3;
    private long nextHoleScan;
    private int maxHoles;
    private int holesClosed;
    private long holeNanos;
    private long holeMaxNanos;
    private int holeExamples;
    /** The sections the last selection draws, real and preview, and the build height it ran with. */
    private LongOpenHashSet drawn = new LongOpenHashSet();
    /** The sections of the selection being applied (ADR-148), {@link #drawn} once it is whole. */
    private LongOpenHashSet nextDrawn = new LongOpenHashSet();
    /**
     * A selection's draw list is applied a slice a frame (ADR-148): resident
     * lookups, the GPU/CPU split and the extent took 3 to 12 ms a selection
     * in flight (25,000 sections, cache misses), a frame each time. The last
     * applied lists are drawn meanwhile.
     */
    private static final long APPLY_BUDGET_NANOS = 1_000_000L;
    private boolean applying;
    private int applyCursor;
    private long applyTime;
    private double applyX;
    private double applyY;
    private double applyZ;
    private double applyExtentSq;
    private double applyNearestSq;
    private int applyOutdated;
    private long applyNanos;
    private int applyMinY;
    private int applyMaxY;
    /** The time of the last selection applied whole: what it drew has a later last use. */
    private long appliedSelectionTime;
    private int selectedMinY;
    private int selectedMaxY;
    private double loggedDetail = 1e-9;
    /** Detail of the options' field of view, unzoomed: sections outside the view split at it at most. */
    private double outsideDetail = Double.MAX_VALUE;
    private double pendingDetail = 1e-9;
    private long selectionTime;
    private double selectedX;
    private double selectedY;
    private double selectedZ;
    private float selectedLookX;
    private float selectedLookY;
    private float selectedLookZ;
    private double lastCamX;
    private double lastCamY;
    private double lastCamZ;
    /** Start of the previous frame and the time its looks took, for the looks' budget. */
    private long lastFrameStart;
    private long lastLooksNanos;
    /** Start of the current backlog of looks (0: none) and its size, for its log line. */
    private long looksBacklogStart;
    /** When new looks last went to the cache file. */
    private long lastLooksAppend;
    private static final long LOOKS_APPEND_NANOS = 30_000_000_000L;
    private int looksFromCache;
    private int looksBacklog;
    /** Fill line: when the join or teleport happened (0: none being timed), and what it was. */
    private long fillStart;
    private String fillCause;
    /** Wanted sections in view at the first selection past each of {@link #FILL_CHECKS}, -1 before it. */
    private final int[] fillTimeline = new int[FILL_CHECKS.length];
    private int fillMeshes;
    /** Since when nothing is missing in view and no mesh is in flight (0: something is). */
    private long fillSettledSince;
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f cullMatrix = new Matrix4f();
    /**
     * The preview's selection, on a thread of its own (ADR-143): at a large
     * distance it walked tens of thousands of preview sections, 5 to 25 ms of
     * the render thread at each selection. Its last result is drawn with each
     * selection of the real sections; a new one starts with them.
     */
    private final java.util.concurrent.ExecutorService previewSelector =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "farfield-preview-selection");
                t.setDaemon(true);
                return t;
            });
    private java.util.concurrent.Future<PreviewSelection> previewJob;
    /** Bumped when a running preview selection's result no longer applies (another world, the preview off). */
    private int previewGeneration;
    private PreviewSelection previewResult = new PreviewSelection(0);
    /** The sections the last selection relied on to descend (ADR-147): never evicted while it stands. */
    private LongOpenHashSet reliedReal = new LongOpenHashSet();
    private LongOpenHashSet reliedPreview = new LongOpenHashSet();

    private boolean relied(long key) {
        return PreviewLayer.isPreview(key) ? reliedPreview.contains(key) : reliedReal.contains(key);
    }

    /** The resident preview sections, kept with {@link #resident}: what a preview selection takes as ready. */
    private final LongOpenHashSet previewResident = new LongOpenHashSet();
    /** The resident real sections and their octants, kept with {@link #resident} for the selection (ADR-147). */
    private final LongOpenHashSet realResident = new LongOpenHashSet();
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap realOctants =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    /**
     * The real sections' existence cache belongs to the selection's thread
     * (ADR-147): the render thread queues the sections the store wrote, and a
     * clear on a new world.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> existenceOps =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean clearExistence =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** The detail and level lock the next selection takes (set on its thread at its start, ADR-147). */
    private double wantedDetail = Double.NaN;
    private int wantedForcedLevel = -1;
    private boolean previewFailureLogged;

    /** A preview selection's result: preview keys to draw and to mesh, and those it relied on as ready. */
    private static final class PreviewSelection {
        final int generation;
        final LongArrayList draw = new LongArrayList();
        final LongArrayList want = new LongArrayList();
        final LongOpenHashSet relied = new LongOpenHashSet();
        /** The real sections' part (ADR-147): drawn, wanted, relied on, and the selector's fallbacks. */
        final LongArrayList realDraw = new LongArrayList();
        final LongArrayList realWant = new LongArrayList();
        final LongOpenHashSet realRelied = new LongOpenHashSet();
        int fallbacks;
        int finerFallbacks;
        final java.util.List<String> reasons = new java.util.ArrayList<>();
        boolean preview;
        long nanos;

        PreviewSelection(int generation) {
            this.generation = generation;
        }

        private long fingerprint;
        private boolean fingerprinted;

        /** What tells two results apart: the keys drawn and wanted, in order; computed once. */
        long fingerprint() {
            if (fingerprinted) {
                return fingerprint;
            }
            fingerprinted = true;
            long h = draw.size() * 31L + want.size() + realDraw.size() * 961L + realWant.size() * 29791L;
            for (int i = 0, n = realDraw.size(); i < n; i++) {
                h = h * 0x9E3779B97F4A7C15L + realDraw.getLong(i);
            }
            for (int i = 0, n = realWant.size(); i < n; i++) {
                h = h * 0x9E3779B97F4A7C15L + realWant.getLong(i);
            }
            for (int i = 0, n = draw.size(); i < n; i++) {
                h = h * 0x9E3779B97F4A7C15L + draw.getLong(i);
            }
            for (int i = 0, n = want.size(); i < n; i++) {
                h = h * 0x9E3779B97F4A7C15L + want.getLong(i);
            }
            fingerprint = h;
            return h;
        }
    }
    private final Matrix4f viewProj = new Matrix4f();
    private final float[] matrix = new float[16];
    /** Draw commands per list ({@link #SOLID}, {@link #DISCARD}, {@link #TRANSLUCENT}, {@link #PLANTS}), in indirect buffer order. */
    private final IntBuffer[] commands = {MemoryUtil.memAllocInt(4 * 1024), MemoryUtil.memAllocInt(4 * 1024),
            MemoryUtil.memAllocInt(4 * 1024), MemoryUtil.memAllocInt(1024)};
    private final int[] draws = new int[4];

    // Statistics for the periodic log line.
    private long frame;
    private long frameNanos;
    /** The longest LOD frame on the render thread, the longest time between two frames, since the last stat line. */
    private long maxFrameNanos;
    private long maxFrameGapNanos;
    private long previousFrameStart;
    /** Frames that came more than {@link #LONG_FRAME_NANOS} after the one before, since the last stat line. */
    private int longFrames;
    private static final long LONG_FRAME_NANOS = 50_000_000L;
    /** The longest preview selection on its thread since the last stat line (ADR-143). */
    private long previewSelectNanos;
    /** Each step's mean time a frame, in microseconds, since the last stat line (ADR-146). */
    private String stepMeans(long frames) {
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < PHASES.length; i++) {
            out.append(i == 0 ? "" : ", ").append(PHASES[i]).append(' ').append(stepNanos[i] / 1000 / Math.max(1, frames));
        }
        return out.append(" us)").toString();
    }

    /** The steps of a frame, timed for {@link #worstFrame} (ADR-141). */
    private static final String[] PHASES = {"blends", "rewrites", "uploads", "colors", "selection", "wanted",
            "commands", "eviction", "draw"};
    /** A constant, not {@code PHASES.length}: the instance is built before the array in the class's init. */
    private static final int TIMED_PHASES = 8;
    private final long[] phase = new long[TIMED_PHASES];
    /** Each step's time summed over the frames since the last stat line, for its mean (ADR-146). */
    private final long[] stepNanos = new long[TIMED_PHASES + 1];
    /** Within this frame's uploads: the quads sent and the biome blends (ADR-144). */
    private long uploadQuadsNanos;
    private long uploadBlendNanos;
    /** The steps of the longest LOD frame since the last stat line. */
    private String worstFrame = "";
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
    private long plantQuadsSubmitted;
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
        final int hiddenMin;
        final int lightmap;
        final int fogColor;
        final int fog;
        final int fogCylinder;
        final int translucent;
        final int debugLevels;
        final int ambientOcclusion;
        final int biomeBlend;
        final int atlas;
        final int atlasSize;
        final int textureEnd;
        final int gameDepth;
        /** Linked with a shader pack's code: binds the pack's uniforms too (ADR-113). */
        final boolean pack;

        Shader(String defines) {
            this(defines, null, null, null);
        }

        Shader(String defines, String packVertex, String packUnit, String packName) {
            this(new GlProgram("lod.vert", "lod.frag", defines, packVertex, packUnit, packName),
                    packUnit != null || packVertex != null);
        }

        Shader(GlProgram program, boolean pack) {
            this.program = program;
            this.pack = pack;
            viewProj = program.uniform("u_viewProj");
            cameraBlock = program.uniform("u_cameraBlock");
            cameraFrac = program.uniform("u_cameraFrac");
            depthRange = program.uniform("u_depthRange");
            renderDistance = program.uniform("u_renderDistance");
            gridMin = program.uniform("u_gridMin");
            gridSize = program.uniform("u_gridSize");
            sodiumRule = program.uniform("u_sodiumRule");
            hiddenMin = program.uniform("u_hiddenMin");
            lightmap = program.uniform("u_lightmap");
            fogColor = program.uniform("u_fogColor");
            fog = program.uniform("u_fog");
            fogCylinder = program.uniform("u_fogCylinder");
            translucent = program.uniform("u_translucent");
            debugLevels = program.uniform("u_debugLevels");
            ambientOcclusion = program.uniform("u_ambientOcclusion");
            biomeBlend = program.uniform("u_biomeBlend");
            atlas = program.uniform("u_atlas");
            atlasSize = program.uniform("u_atlasSize");
            textureEnd = program.uniform("u_textureEnd");
            gameDepth = program.uniform("u_gameDepth");
        }
    }

    private static final class Resident {
        final int slot;
        long start;
        int quadCount;
        /** The first quads of the range are opaque, then translucent ones, then plants. */
        int opaqueCount;
        /** Start of each quad group in the range ({@link #GROUPS}), then {@link #quadCount}. */
        int[] groups = new int[GROUPS + 1];
        /** Some opaque faces have holes to cut out (or were meshed before their colors were built). */
        boolean cutout;
        /** Highest material of its quads (-1 for none). */
        int maxMaterial = -1;
        /** The materials of its quads: drawn once all their looks are built (ADR-078). */
        int[] materials = new int[0];
        /** {@link MaterialColors#version} {@link #looksReady} was found at. */
        int looksVersion = -1;
        boolean looksReady;
        /** Its opaque voxels are in the occupancy buffer (ambient occlusion). */
        boolean occluders;
        /** Its columns' surface biomes as uploaded ({@link #BIOME_COLUMN_INTS}), for blending it and its neighbors. */
        int[] biomeColumns;
        /** Directions whose neighbor lights some of its faces ({@link BinaryGreedyMesher#borderFaces}). */
        int borderFaces;
        /** The octants its quads draw in: a parent showing a new child's octant covers it until it is meshed. */
        int octants;
        /** The chunk columns its quads draw in ({@link Quad#markColumns}); null without quads. */
        long[] columns;
        /** Per direction, a hash of its layer a neighbor there reads; null before its first mesh. */
        long[] borders;

        Resident(int slot) {
            this.slot = slot;
        }
    }

    /** {@code groups}: start of each quad group ({@link #GROUPS}), then the quad count. */
    /** {@code provisional}: {@code cutout} was guessed for materials whose looks were not built yet. */
    /** {@code octants}: the octants its quads draw in ({@link Quad#octants}). */
    private record MeshResult(int generation, long key, long[] quads, int[] groups, boolean cutout,
                              boolean provisional, int[] occupancy, int[] biomeColumns,
                              int maxMaterial, int[] materials, int borderFaces, int octants, long[] columns,
                              long[] borders) {
    }

    private static final int[] NO_GROUPS = new int[GROUPS + 1];
    private static final int[] NO_MATERIALS = new int[0];

    private static MeshResult emptyMesh(int generation, long key) {
        return new MeshResult(generation, key, new long[0], NO_GROUPS, false, false, null, null, -1, NO_MATERIALS, 0, 0,
                null, null);
    }

    private LodRenderer() {
    }

    /**
     * {@code /farfieldclient debug here}: follows the view from the camera to
     * the first stored opaque voxel or the preview's surface, and logs what
     * each level, the stream, the preview and the game know there, so a hole
     * can be reported by pointing at it. Returns the line for the chat.
     */
    public static String describeLook() {
        return INSTANCE.lookReport(Minecraft.getInstance());
    }

    private String lookReport(Minecraft mc) {
        LodWorld w = world;
        if (w == null || mc.level == null) {
            return "No LOD world is attached.";
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 from = camera.getPosition();
        org.joml.Vector3f look = camera.getLookVector();
        MaterialClassifier classes = w.materials();
        int minY = mc.level.getMinBuildHeight();
        int maxY = mc.level.getMaxBuildHeight();
        Long2ObjectOpenHashMap<LodSection> sections = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet absent = new LongOpenHashSet();
        Long2ObjectOpenHashMap<PreviewTile> tiles = new Long2ObjectOpenHashMap<>();
        int hx = 0;
        int hy = 0;
        int hz = 0;
        int realLevel = -1;
        double distance = -1;
        int lastX = Integer.MIN_VALUE;
        int lastY = 0;
        int lastZ = 0;
        try {
            for (double t = 0; t < LOOK_DISTANCE; t += 0.25) {
                int x = Mth.floor(from.x + look.x() * t);
                int y = Mth.floor(from.y + look.y() * t);
                int z = Mth.floor(from.z + look.z() * t);
                if (x == lastX && y == lastY && z == lastZ) {
                    continue;
                }
                lastX = x;
                lastY = y;
                lastZ = z;
                if (y >= maxY && look.y() >= 0 || y < minY && look.y() <= 0) {
                    break;
                }
                if (y < minY || y >= maxY) {
                    continue;
                }
                // The finest stored level decides: a coarse voxel is far bigger than what it holds.
                for (int level = 0; level < LodWorld.LEVELS && realLevel < 0; level++) {
                    long key = LodKey.pack(level, x >> (5 + level), y >> (5 + level), z >> (5 + level));
                    LodSection s = sections.get(key);
                    if (s == null && !absent.contains(key)) {
                        s = w.exists(key) ? w.read(key) : null;
                        if (s == null) {
                            absent.add(key);
                        } else {
                            sections.put(key, s);
                        }
                    }
                    if (s != null) {
                        int c = classes.classOf(s.material((x >> level) & 31, (y >> level) & 31, (z >> level) & 31));
                        if (c != MaterialClassifier.AIR && c != MaterialClassifier.TRANSLUCENT
                                && c != MaterialClassifier.PLANT) {
                            realLevel = level;
                        }
                        break;
                    }
                }
                int tileX = Math.floorDiv(x, PreviewTile.BLOCKS);
                int tileZ = Math.floorDiv(z, PreviewTile.BLOCKS);
                long tileKey = PreviewStore.key(tileX, tileZ);
                if (!tiles.containsKey(tileKey)) {
                    tiles.put(tileKey, w.preview().read(tileX, tileZ));
                }
                PreviewTile tile = tiles.get(tileKey);
                boolean previewHit = tile != null
                        && y < tile.height(Math.floorMod(x, PreviewTile.BLOCKS), Math.floorMod(z, PreviewTile.BLOCKS));
                if (realLevel >= 0 || previewHit) {
                    hx = x;
                    hy = y;
                    hz = z;
                    distance = t;
                    break;
                }
            }
        } catch (IOException e) {
            return "Could not read the LODs: " + e.getMessage();
        }
        if (distance < 0) {
            return "Nothing stored and no preview surface along the view within " + LOOK_DISTANCE + " blocks.";
        }
        String what = realLevel >= 0 ? "stored terrain (level " + realLevel + ")" : "the preview's surface, no stored terrain";
        String head = "Here: " + hx + " " + hy + " " + hz + ", " + Math.round(distance) + " blocks away, " + what;
        StringBuilder log = new StringBuilder(head).append(" (camera ").append(Math.round(from.x)).append(' ')
                .append(Math.round(from.y)).append(' ').append(Math.round(from.z)).append(')');
        for (int level = 0; level < LodWorld.LEVELS; level++) {
            long key = LodKey.pack(level, hx >> (5 + level), hy >> (5 + level), hz >> (5 + level));
            LodSection s = sections.get(key);
            String voxel;
            if (s == null) {
                voxel = absent.contains(key) ? "no stored section" : "section not read";
            } else {
                int m = s.material((hx >> level) & 31, (hy >> level) & 31, (hz >> level) & 31);
                voxel = "voxel " + w.blockStates().key(m) + " (class " + classes.classOf(m) + ")";
            }
            log.append("\n  level ").append(level).append(' ').append(LodKey.x(key)).append(',').append(LodKey.y(key))
                    .append(',').append(LodKey.z(key)).append(": ").append(voxel)
                    .append("; stream: ").append(stream != null ? stream.describe(key) : "none (single player)")
                    .append("; renderer: ").append(rendererState(key))
                    .append("; preview section: ").append(rendererState(PreviewLayer.toPreview(key)));
        }
        log.append("\n  preview chunk: ").append(previewLayer.describeChunk(hx >> 4, hz >> 4));
        log.append("\n  game: ").append(gameState(mc, hx >> 4, hy >> 4, hz >> 4, from));
        Farfield.LOG.info("LOD debug here: {}", log);
        return head + " (details in the log).";
    }

    private String rendererState(long key) {
        Resident r = resident.get(key);
        String state = r == null ? "not meshed" : "meshed, " + r.quadCount + " quads";
        if (drawList.contains(key)) {
            state += ", drawn";
        }
        if (requested.contains(key)) {
            state += ", meshing";
        }
        if (stale.contains(key)) {
            state += ", stale";
        }
        if (wanted.contains(key)) {
            state += ", wanted";
        }
        return state;
    }

    private String gameState(Minecraft mc, int sx, int sy, int sz, Vec3 cam) {
        boolean loaded = mc.level.getChunkSource().getChunk(sx, sz, false) != null;
        int gx = sx - gridMinX;
        int gy = sy - gridMinY;
        int gz = sz - gridMinZ;
        if (gx < 0 || gy < 0 || gz < 0 || gx >= gridWidth || gz >= gridWidth || gy >= gridHeight) {
            return "chunk " + (loaded ? "loaded" : "not loaded") + ", outside the compiled-sections grid";
        }
        int bit = (gz * gridWidth + gx) * gridHeight + gy;
        boolean compiled = (compiledBits[bit >> 5] >>> (bit & 31) & 1) != 0;
        boolean givesWay = (drawnBits[bit >> 5] >>> (bit & 31) & 1) != 0;
        int rd = mc.options.getEffectiveRenderDistance();
        int camX = Mth.floor(cam.x);
        int camY = Mth.floor(cam.y);
        int camZ = Mth.floor(cam.z);
        double nx = nearest(sx * 16 - camX, cam.x - camX);
        double ny = nearest(sy * 16 - camY, cam.y - camY);
        double nz = nearest(sz * 16 - camZ, cam.z - camZ);
        double d = rd * 16;
        boolean inReach = nx * nx + nz * nz < d * d && Math.abs(ny) < d;
        return "chunk " + (loaded ? "loaded" : "not loaded") + ", section " + (compiled ? "compiled" : "not compiled")
                + ", in the drawn grid: " + (givesWay ? "yes" : "no") + ", within the render distance ("
                + (sodiumRule ? "Sodium" : "vanilla") + " terrain, Sodium rule): " + (inReach ? "yes" : "no");
    }

    /**
     * What the agent inbox reads (ADR-095), client thread.
     *
     * @param disabled      LOD rendering turned itself off (no OpenGL 4.6, a failed start)
     * @param drawing       a LOD world is attached and drawn
     * @param drawn         sections the last frame drew
     * @param wantedInView  sections in view the last selection wanted and had no mesh for
     * @param meshing       mesh jobs in flight
     * @param holesOpen     chunk columns near the camera with nothing drawn (ADR-077 detector)
     */
    public record Snapshot(boolean disabled, boolean drawing, int drawn, int resident, int wantedInView, int meshing, int holesOpen) {
    }

    public static Snapshot snapshot() {
        LodRenderer r = INSTANCE;
        return new Snapshot(r.disabled, drawsBeyondVanilla(), r.lastDrawn, r.resident.size(), r.lastWantedInView,
                r.requested.size(), r.holeSince.size());
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
        int distance = thickFogDimension ? RenderSettings.thickFogDistance() : RenderSettings.drawDistance();
        if (distance == RenderConfig.FOG_OFF) {
            return RenderConfig.DEFAULT_DRAW_DISTANCE; // no fog: the 4,096 blocks of before E.3b
        }
        if (distance == RenderConfig.FOG_VANILLA) {
            return 0; // the game's thick fog is opaque within its render distance
        }
        if (packLink != null && (packLink.mode() == ShaderPackLink.VOXY || packLink.mode() == ShaderPackLink.DH)
                && distance > PACK_MAX_DISTANCE) {
            if (!packCapLogged) {
                packCapLogged = true;
                Farfield.LOG.info("Shader pack LODs: drawn to {} blocks, not {}: past about 800,000 blocks a pack's"
                        + " combined depth (Photon's, near plane 0.05) holds nothing and draws black (ADR-141)",
                        PACK_MAX_DISTANCE, distance);
            }
            return PACK_MAX_DISTANCE;
        }
        return Math.min(MAX_DISTANCE, distance);
    }

    /** Called from the level renderer mixin, after the sky, before the terrain. */
    /**
     * At the start of the level's frame: the shader pack's LOD state for it
     * (ADR-113), set even when no LOD draws.
     */
    public static void beginLevel(Matrix4f viewRotation, Matrix4f projection) {
        LodRenderer r = INSTANCE;
        r.translucentPending = false;
        // The depth range follows the drawn extent (ADR-140): a fixed far plane at twice the maximum
        // (67 million blocks) against a near plane of 8 lost the packs' inverse projection in float,
        // and Photon drew black squares over the LODs at every distance.
        r.depthFar = (float) Math.max(1024, 2 * r.drawnExtent);
        // The near plane at half the nearest drawn geometry (ADR-141): the LODs' depth is cleared before the
        // game's terrain draws, so nothing nearer needs it; seen from 2 million blocks up, a near plane of 64
        // left the preview's surfaces fighting over a few thousand blocks of depth (a grey cast over it).
        r.depthNear = (float) Math.max(Math.max(8, r.drawnNearest * 0.5), r.depthFar / DEPTH_RATIO);
        if (r.packLink != null && !r.disabled) {
            try {
                // The farthest LOD geometry, not the selection's radius: square sections cross it, and
                // packs put their LOD far plane there (Photon's combined_far = 16 * vxRenderDistance), so
                // geometry past it came out as fog.
                r.packLink.frame(projection, viewRotation, r.depthNear, r.depthFar, (int) Math.ceil(r.drawnExtent));
            } catch (RuntimeException e) {
                r.packFailed(e);
            }
        }
    }

    public static void render(Camera camera, Matrix4f viewRotation, Matrix4f projection) {
        FrameProbe.beforeLods(); // glitch watch (ADR-099), off by default
        INSTANCE.renderFrame(camera, viewRotation, projection);
        FrameProbe.afterLods();
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
            plants = new Shader("#define PLANTS\n");
        } catch (RuntimeException e) {
            disabled = true;
            Farfield.LOG.error("LOD rendering disabled: shader setup failed", e);
            return false;
        }
        sodiumRule = classExists("net.caffeinemc.mods.sodium.client.SodiumClientMod")
                || classExists("org.embeddedt.embeddium.impl.Embeddium")
                || classExists("me.jellysquid.mods.sodium.client.SodiumClientMod");
        Farfield.LOG.info("LOD hand-off follows the {} section rule", sodiumRule ? "Sodium" : "vanilla");
        packLink = createPackLink();
        if (packLink != null && pendingPackReload) {
            // A contract forced before the renderer started: the pack loads again with it.
            pendingPackReload = false;
            Minecraft.getInstance().execute(packLink::reloadPack);
        }
        glGenQueries(timerQueries);
        glGenQueries(packTranslucentQueries);
        startQuads = rememberedQuads();
        startedNanos = System.nanoTime();
        quads = new GlBuffer(startQuads * 8, false);
        arena = new RangeAllocator(startQuads);
        // The per-slot buffers at the slots the last session used (ADR-144): each doubling is a copy.
        long slots = Math.max(1024, rememberedSlots);
        sectionInfo = new GlBuffer(Math.max(4096, slots) * SECTION_BYTES);
        colorBuffer = new GlBuffer(4096L * MaterialColors.STRIDE * 4);
        biomeBuffer = new GlBuffer(256L * BiomeTints.KINDS * 4);
        occupancy = new GlBuffer(slots * OCCUPANCY_INTS * 4);
        neighborSlots = new GlBuffer(slots * NEIGHBOR_INTS * 4);
        biomeColumns = new GlBuffer(slots * BIOME_COLUMN_INTS * 4);
        blendedTints = new GlBuffer(slots * BLEND_SLOT_INTS * 4);
        drawnSections = new GlBuffer(4096L * 4);
        previewHidden = new GlBuffer((long) HIDDEN_SIDE * HIDDEN_SIDE / 8);
        indirect = new GlBuffer(1024L * COMMAND_BYTES);
        cullGroups = new GlBuffer(Math.max(4096, slots) * CULL_INTS * 4);
        cullCandidates = new GlBuffer(4096L * 4);
        gpuCommands = new GlBuffer(3L * 4096 * COMMAND_BYTES);
        gpuCounts = new GlBuffer(16);
        scatterPairs = new GlBuffer(16384L * 8);
        try {
            scatterProgram = GlProgram.compute("lod_scatter.comp");
            scatterCountU = scatterProgram.uniform("u_count");
        } catch (RuntimeException e) {
            scatterProgram = null;
            Farfield.LOG.warn("LOD neighbor writes in one pass unavailable, written one by one", e);
        }
        if (!Boolean.getBoolean("farfield.cpuCulling")) {
            try {
                cullProgram = GlProgram.compute("lod_cull.comp");
                cullCountU = cullProgram.uniform("u_count");
                cullMaxU = cullProgram.uniform("u_maxCommands");
                cullPlanesU = cullProgram.uniform("u_planes");
                cullCameraBlockU = cullProgram.uniform("u_cameraBlock");
                cullCameraFracU = cullProgram.uniform("u_cameraFrac");
                cullPlantsU = cullProgram.uniform("u_plants");
                Farfield.LOG.info("LOD far sections culled on the GPU");
            } catch (RuntimeException e) {
                cullProgram = null;
                Farfield.LOG.warn("LOD GPU culling unavailable, the CPU culls every section", e);
            }
        }
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
        Farfield.LOG.info("LOD renderer ready on {}, OpenGL {} ({} MB of quads to start, {} meshes in flight at most)",
                glGetString(GL_RENDERER), glGetString(GL_VERSION), startQuads * 8 >> 20, MAX_IN_FLIGHT);
        return true;
    }

    /** The shader pack path over Iris (ADR-113), or null without Iris or with Voxy itself. */
    private static ShaderPackLink createPackLink() {
        if (!Services.PLATFORM.isModLoaded("iris") || Services.PLATFORM.isModLoaded("voxy")) {
            return null;
        }
        try {
            ShaderPackLink link = (ShaderPackLink) Class.forName("io.lakio.farfield.client.render.iris.IrisLink")
                    .getConstructor(int[].class).newInstance((Object) new int[]{ATLAS_UNIT, LIGHTMAP_UNIT, GAME_DEPTH_UNIT});
            Farfield.LOG.info("Shader pack LODs: Iris found, the packs' Voxy or Distant Horizons contract is read");
            return link;
        } catch (ReflectiveOperationException | LinkageError e) {
            Farfield.LOG.warn("Shader pack LODs off: this Iris does not fit ({})", e.toString());
            return null;
        }
    }

    /** The variants linked with the pack's code, built again when the pack's sources change. */
    private boolean packShaders() {
        if (packGeneration == packLink.generation() && packShaded != null) {
            return true;
        }
        closePackShaders();
        packGeneration = packLink.generation();
        String parameters = GlProgram.resource("voxy_parameters.glsl");
        String unit = packLink.opaqueUnit(parameters);
        if (unit == null) {
            return false;
        }
        String opaqueName = "the shader pack's voxy_opaque";
        String vertex = packNoTaa == packGeneration ? null : packLink.vertexUnit();
        String taa = vertex != null ? "#define VOXY_TAA\n" : "";
        try {
            packShaded = new Shader("#define EARLY_Z\n#define VOXY\n" + taa, vertex, unit, opaqueName);
            packPlain = new Shader("#define VOXY\n" + taa, vertex, unit, opaqueName);
            packPlants = new Shader("#define PLANTS\n#define VOXY\n" + taa, vertex, unit, opaqueName);
            if (vertex != null) {
                packDepthSolid = new Shader("#define DEPTH_ONLY\n#define SOLID\n" + taa, vertex, null, "the shader pack's taaOffset");
                packDepthOnly = new Shader("#define DEPTH_ONLY\n" + taa, vertex, null, "the shader pack's taaOffset");
            }
            for (Shader s : new Shader[]{packShaded, packPlain, packPlants, packDepthSolid, packDepthOnly}) {
                if (s != null) {
                    packLink.attach(s.program.id(), "farfield_lod", false);
                }
            }
        } catch (RuntimeException e) {
            if (vertex != null) {
                // The jitter is the least of it: the LODs without it rather than none.
                Farfield.LOG.warn("Shader pack LODs: built again without the pack's taaOffset ({})",
                        e.getMessage().lines().findFirst().orElse(""));
                closePackShaders();
                packNoTaa = packGeneration;
                return packShaders();
            }
            Farfield.LOG.error("Shader pack LODs: the pack's voxy_opaque does not build with the LOD shader", e);
            closePackShaders();
            packLink.fail("its voxy_opaque does not build with the LOD shader (" + e.getMessage().lines().findFirst().orElse("") + ")");
            return false;
        }
        // Without a translucent snippet that builds, the translucent LODs stay off; the opaque ones draw.
        String translucentUnit = packLink.translucentUnit(parameters);
        if (translucentUnit != null) {
            try {
                packTranslucent = new Shader("#define VOXY\n#define VOXY_TRANSLUCENT\n" + taa, vertex, translucentUnit,
                        "the shader pack's voxy_translucent");
                packLink.attach(packTranslucent.program.id(), "farfield_lod_translucent", true);
            } catch (RuntimeException e) {
                Farfield.LOG.error("Shader pack LODs: the pack's voxy_translucent does not build with the LOD shader,"
                        + " translucent LODs off under it", e);
                packTranslucent = null;
            }
        }
        Farfield.LOG.info("Shader pack LODs: programs built, translucent LODs {}, TAA jitter {}",
                packTranslucent != null ? "on" : "off", vertex != null ? "on" : "off");
        return true;
    }

    /**
     * Forces a shader pack's LOD contract (/farfield debug shaderpack, not
     * saved) and loads the pack again so it takes effect: a pack shipping
     * both is then tested through its DH contract (ADR-119).
     *
     * @return the reply line
     */
    public static String forcePackContract(int contract) {
        LodRenderer r = INSTANCE;
        if (!Services.PLATFORM.isModLoaded("iris") || Services.PLATFORM.isModLoaded("voxy")) {
            return "No shader pack path here (Iris missing, or Voxy installed).";
        }
        io.lakio.farfield.client.render.iris.PackContract.force(contract);
        if (r.packLink == null) {
            r.pendingPackReload = true;
            Farfield.LOG.info("Shader pack LODs: contract forced to {} by command, applied when the LOD renderer starts",
                    io.lakio.farfield.client.render.iris.PackContract.name(contract));
            return "Shader pack LOD contract forced: " + io.lakio.farfield.client.render.iris.PackContract.name(contract)
                    + " (not saved), applied when the LOD renderer starts.";
        }
        Farfield.LOG.info("Shader pack LODs: contract forced to {} by command, the pack loads again",
                io.lakio.farfield.client.render.iris.PackContract.name(contract));
        // Iris loads packs on the render thread; /farfield runs on the server thread in single player.
        ShaderPackLink link = r.packLink;
        Minecraft.getInstance().execute(link::reloadPack);
        return "Shader pack LOD contract forced: " + io.lakio.farfield.client.render.iris.PackContract.name(contract)
                + " (not saved). The pack loads again; the log names the contract followed.";
    }

    /** The pack's DH programs linked with the bridge, built again when the pack's sources change. */
    private boolean dhShaders() {
        if (dhGeneration == packLink.generation() && dhOpaque != null) {
            return true;
        }
        closeDhShaders();
        dhGeneration = packLink.generation();
        String[] opaque = packLink.dhSources(false);
        if (opaque == null) {
            return false;
        }
        String bridgeVertex = GlProgram.resource("dh_bridge.vert");
        String bridgeFragment = GlProgram.resource("dh_bridge.frag");
        try {
            dhOpaque = new Shader(new GlProgram("the shader pack's " + opaque[2] + " with the LOD bridge",
                    new String[]{opaque[0], bridgeVertex}, new String[]{opaque[1], bridgeFragment}), true);
            packLink.attach(dhOpaque.program.id(), "farfield_" + opaque[2], false);
        } catch (RuntimeException e) {
            Farfield.LOG.error("Shader pack LODs: the pack's {} does not build with the LOD bridge", opaque[2], e);
            closeDhShaders();
            packLink.fail("its " + opaque[2] + " does not build with the LOD bridge ("
                    + e.getMessage().lines().findFirst().orElse("") + ")");
            return false;
        }
        String[] water = packLink.dhSources(true);
        try {
            dhTranslucent = new Shader(new GlProgram("the shader pack's " + water[2] + " with the LOD bridge",
                    new String[]{water[0], bridgeVertex}, new String[]{water[1], bridgeFragment}), true);
            packLink.attach(dhTranslucent.program.id(), "farfield_" + water[2] + "_translucent", true);
        } catch (RuntimeException e) {
            Farfield.LOG.error("Shader pack LODs: the pack's {} does not build with the LOD bridge,"
                    + " translucent LODs off under it", water[2], e);
            dhTranslucent = null;
        }
        Farfield.LOG.info("Shader pack LODs: DH programs built with the LOD bridge ({} and {}), translucent LODs {}",
                opaque[2], water[2], dhTranslucent != null ? "on" : "off");
        return true;
    }

    private void closeDhShaders() {
        for (Shader s : new Shader[]{dhOpaque, dhTranslucent}) {
            if (s != null) {
                packLink.detach(s.program.id());
                s.program.close();
            }
        }
        dhOpaque = dhTranslucent = null;
    }

    private void closePackShaders() {
        for (Shader s : new Shader[]{packShaded, packPlain, packPlants, packTranslucent, packDepthSolid, packDepthOnly}) {
            if (s != null) {
                packLink.detach(s.program.id());
                s.program.close();
            }
        }
        packShaded = packPlain = packPlants = packTranslucent = packDepthSolid = packDepthOnly = null;
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
        stream = StreamClient.of(world);
        if (RenderSettings.forcedLevel() != wantedForcedLevel) {
            wantedForcedLevel = RenderSettings.forcedLevel(); // the level lock, a diagnostic: the next selection takes it
            selectionDirty = true;
        }
        if (stream != null && stream.generation() != streamGeneration) {
            streamGeneration = stream.generation(); // region indexes arrived: more sections exist
            selectionDirty = true;
        }
        long frameStart = System.nanoTime();
        RenderSettings.resize(meshers, RenderSettings.effectiveThreads(RenderSettings.meshThreads()), "LOD mesher");
        frame++;
        readGpuTimers();
        int uploadedBefore = meshesUploaded;
        long growBefore = GlBuffer.growNanos;
        uploadQuadsNanos = 0;
        uploadBlendNanos = 0;
        updateBlends();
        phase[0] = System.nanoTime();
        applyRewrites();
        phase[1] = System.nanoTime();
        uploadMeshes();
        phase[2] = System.nanoTime();
        uploadColors(mc, level, looksBudget(frameStart));
        updateDrawnSections(mc, level, camera);
        phase[3] = System.nanoTime();

        Vec3 cam = camera.getPosition();
        double jx = cam.x - lastCamX;
        double jy = cam.y - lastCamY;
        double jz = cam.z - lastCamZ;
        if (jx * jx + jy * jy + jz * jz > TELEPORT_DISTANCE_SQ) {
            startFill(frameStart, "a teleport");
        }
        lastCamX = cam.x;
        lastCamY = cam.y;
        lastCamZ = cam.z;
        double dx = cam.x - selectedX;
        double dy = cam.y - selectedY;
        double dz = cam.z - selectedZ;
        org.joml.Vector3f look = camera.getLookVector();
        boolean turned = look.x * selectedLookX + look.y * selectedLookY + look.z * selectedLookZ < RESELECT_TURN_COS;
        updateDetail(mc, projection);
        double distance = selectionDistance();
        if (distance != lastSelectionDistance) {
            lastSelectionDistance = distance;
            selectionDirty = true;
        }
        if (RenderSettings.noisePreview() != selectedPreview) {
            selectionDirty = true;
        }
        if (RenderSettings.previewLight() != meshedPreviewLight
                || RenderSettings.previewFeatures() != meshedPreviewFeatures) {
            // The preview's light and features are in its sections: mesh them again, the old meshes
            // drawn meanwhile.
            boolean light = RenderSettings.previewLight() != meshedPreviewLight;
            meshedPreviewLight = RenderSettings.previewLight();
            meshedPreviewFeatures = RenderSettings.previewFeatures();
            int n = 0;
            for (long key : resident.keySet()) {
                if (PreviewLayer.isPreview(key)) {
                    stale.add(key);
                    n++;
                }
            }
            selectionDirty = true;
            if (light) {
                Farfield.LOG.info("LOD preview light switched {}: {} preview sections meshed again",
                        meshedPreviewLight ? "on" : "off", n);
            } else {
                Farfield.LOG.info("LOD preview features now {}: {} preview sections meshed again",
                        PreviewLayer.featuresText(meshedPreviewFeatures), n);
            }
        }
        updateFrustum(viewRotation, projection);
        if (stream != null) {
            stream.askRegions(); // the regions the selection's thread wanted an index of (ADR-147)
        }
        if (takePreviewSelection()) {
            selectionDirty = true; // a new selection result: drawn with the next selection
        }
        // At most one selection per MIN_RESELECT_NANOS (ADR-140): in flight each new mesh made the
        // next frame select again, a selection every frame.
        if (applying) {
            continueApply(System.nanoTime() + APPLY_BUDGET_NANOS);
        } else if ((selectionDirty || turned || dx * dx + dy * dy + dz * dz > RESELECT_DISTANCE_SQ
                || frameStart - selectionTime > RESELECT_NANOS)
                && (selectionTime == 0 || frameStart - selectionTime
                        >= Math.max(MIN_RESELECT_NANOS, SELECTION_SHARE * lastSelectNanos))) {
            select(level, cam, look, frameStart);
        }
        phase[4] = System.nanoTime();
        requestWanted();
        // The hole detector is a diagnostic: it runs only with the performance logs on.
        if (!RenderSettings.performanceLogs()) {
            holeSince.clear(); // turned on again later, old holes would be timed from back then
        } else if (frameStart > nextHoleScan) {
            nextHoleScan = frameStart + HOLE_SCAN_NANOS;
            scanHoles(frameStart, mc.options.getEffectiveRenderDistance());
        }
        updateHandOver(cam, frameStart);
        watchColumns(mc.options.getEffectiveRenderDistance());
        logFill(frameStart, false);
        phase[5] = System.nanoTime();
        for (int l = 0; l < commands.length; l++) {
            commands[l].clear();
            draws[l] = 0;
        }
        int count = 0;
        int renderDistance = mc.options.getEffectiveRenderDistance();
        translucentOrder.clear();
        frameTranslucent.clear();
        for (int i = 0, n = cpuList.size(); i < n; i++) {
            long key = cpuList.getLong(i);
            Resident r = resident.get(key);
            if (r == null || r.quadCount == 0) {
                continue;
            }
            if (!looksReady(r)) {
                if (FrameProbe.enabled()) {
                    FrameProbe.note("looks" + probeName(key));
                }
                continue; // a material without its look yet: drawn once all have one (ADR-062, ADR-078)
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
            boolean discards = r.cutout || nearGame(key) || previewOverHandOver(key);
            discardSections += discards ? 1 : 0;
            addCommands(r, discards ? DISCARD : SOLID, faces);
            // Plants' diagonals face every way and always cut their holes: their own pass,
            // so a section with grass keeps its faces in the solid one (ADR-059).
            if (RenderSettings.drawPlants()) {
                plantQuadsSubmitted += r.groups[PLANT_GROUP + 1] - r.groups[PLANT_GROUP];
                addCommand(PLANTS, r.groups[PLANT_GROUP], r.groups[PLANT_GROUP + 1], r.slot);
            }
            if (r.groups[PLANT_GROUP] > r.opaqueCount) {
                translucentOrder.add((long) Float.floatToIntBits(centerDistanceSq(key, cam)) << 32
                        | (long) frameTranslucent.size() << 6 | faces);
                frameTranslucent.add(key);
            }
        }
        // The GPU-culled sections' translucent faces, sorted with the others (ADR-146).
        for (int i = 0, n = gpuTranslucent.size(); i < n; i++) {
            long key = gpuTranslucent.getLong(i);
            Resident r = resident.get(key);
            if (r == null || r.quadCount == 0) {
                continue;
            }
            int faces = visibleFaces(key, cam);
            if (faces == 0) {
                continue;
            }
            translucentOrder.add((long) Float.floatToIntBits(centerDistanceSq(key, cam)) << 32
                    | (long) frameTranslucent.size() << 6 | faces);
            frameTranslucent.add(key);
        }
        count += gpuCandidates; // culled on the GPU: counted before its frustum test
        // Translucent sections far to near, as vanilla: a ray through one section's ice
        // can reach a neighbor's water surface, which must be blended first (ADR-055).
        // Positive floats sort as their bits.
        java.util.Arrays.sort(translucentOrder.elements(), 0, translucentOrder.size());
        for (int i = translucentOrder.size() - 1; i >= 0; i--) {
            long e = translucentOrder.getLong(i);
            addCommands(resident.get(frameTranslucent.getLong((int) (e & 0xFFFFFFFFL) >>> 6)), TRANSLUCENT, (int) e & 63);
        }
        phase[6] = System.nanoTime();
        if (frame % 60 == 0 || evictBacklog) {
            evictBacklog = evictUnused(frameStart);
            if (frame % 60 == 0) {
                shrinkArena(frameStart);
            }
        }
        phase[7] = System.nanoTime();
        lastDrawn = count;
        flushNeighborWrites();
        if (count > 0) {
            cullOnGpu(cam);
            draw(cam, viewRotation, projection, mc);
        }
        long took = System.nanoTime() - frameStart;
        frameNanos += took;
        long stepStart = frameStart;
        for (int i = 0; i < PHASES.length; i++) {
            long end = i < phase.length ? phase[i] : frameStart + took;
            stepNanos[i] += end - stepStart;
            stepStart = end;
        }
        if (took > maxFrameNanos) {
            // The steps of the longest frame (ADR-141): what a stutter spent its time on.
            long before = frameStart;
            StringBuilder steps = new StringBuilder();
            for (int i = 0; i < PHASES.length; i++) {
                long end = i < phase.length ? phase[i] : frameStart + took;
                steps.append(i == 0 ? "" : ", ").append(PHASES[i]).append(' ').append((end - before) / 1000);
                before = end;
            }
            worstFrame = steps.append(" us; ").append(meshesUploaded - uploadedBefore).append(" meshes uploaded")
                    .append(", buffers grown ").append((GlBuffer.growNanos - growBefore) / 1000).append(" us, quads sent ")
                    .append(uploadQuadsNanos / 1000).append(" us, blends ").append(uploadBlendNanos / 1000).append(" us")
                    .toString();
        }
        maxFrameNanos = Math.max(maxFrameNanos, took);
        // The whole frame, the game's own work included: the stutters a mean hides (ADR-141).
        if (previousFrameStart != 0) {
            maxFrameGapNanos = Math.max(maxFrameGapNanos, frameStart - previousFrameStart);
            if (frameStart - previousFrameStart > LONG_FRAME_NANOS) {
                longFrames++;
            }
        }
        previousFrameStart = frameStart;
        timedFrames++;
        if (frameStart > nextStats) {
            logStats(count);
        }
    }

    /**
     * Rebuilds the grid of sections the terrain renderer has compiled, over
     * the render distance plus one chunk around the camera and the level's
     * height. With Sodium, {@code LevelRenderer.isSectionCompiled} (Sodium
     * overrides it); with vanilla, the sections its renderer draws this frame
     * ({@link #vanillaDrawnSections}).
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
        if (!sodiumRule) {
            vanillaDrawnSections(mc);
            uploadDrawnSections(ints);
            return;
        }
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
        uploadDrawnSections(ints);
    }

    /**
     * Vanilla draws only the compiled sections of its visible list: its
     * occlusion graph, rebuilt in the background after the camera moves 8
     * blocks, cut by the frustum. Compiled was not enough: a section missing
     * from the list for a frame or two while the camera moved showed neither
     * the game's terrain nor the LODs (ADR-052).
     */
    private void vanillaDrawnSections(Minecraft mc) {
        int[] bits = drawnBits;
        java.util.Arrays.fill(bits, 0);
        for (SectionRenderDispatcher.RenderSection s : ((LevelRendererAccessor) mc.levelRenderer).farfield$visibleSections()) {
            if (s.compiled.get() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                continue;
            }
            BlockPos origin = s.getOrigin();
            int gx = (origin.getX() >> 4) - gridMinX;
            int gy = (origin.getY() >> 4) - gridMinY;
            int gz = (origin.getZ() >> 4) - gridMinZ;
            if (gx < 0 || gy < 0 || gz < 0 || gx >= gridWidth || gz >= gridWidth || gy >= gridHeight) {
                continue;
            }
            int bit = (gz * gridWidth + gx) * gridHeight + gy;
            bits[bit >> 5] |= 1 << (bit & 31);
        }
    }

    private void uploadDrawnSections(int ints) {
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
        // A spyglass narrows the projection, not the options' field of view: what lies
        // outside the narrow view keeps the detail of the usual one (ADR-063).
        double fov = Math.toRadians(Math.max(1, mc.options.fov().get()));
        double usualFocal = mc.getWindow().getHeight() / 2.0 / Math.tan(fov / 2);
        outsideDetail = Math.max(1, usualFocal / (LodSection.SIZE * RenderSettings.voxelPixels()));
        float focal = mc.getWindow().getHeight() * Math.abs(projection.m11()) / 2;
        if (!Float.isFinite(focal) || focal <= 0) {
            return; // degenerate projection (seen on the first frame): keep the last detail
        }
        // Never coarser than the options' field of view (ADR-145): sprinting and flying widen the
        // projection, and each start or stop of a sprint moved the detail by half (7.5 to 4.7 and back
        // in the user's pack), every section's level with it: 15,000 meshes again each 10 s in flight.
        // A spyglass, narrower, still raises it (ADR-063).
        focal = (float) Math.max(focal, usualFocal);
        double detail = Math.max(1, focal / (LodSection.SIZE * RenderSettings.voxelPixels()));
        // A value must hold for two frames: the first frame's projection can be off
        // (seen at 20 times the right detail), which would request level-0 meshes for nothing.
        if (Math.abs(detail - pendingDetail) > pendingDetail * 0.02) {
            pendingDetail = detail;
            return;
        }
        double current = Double.isNaN(wantedDetail) ? selector.detail() : wantedDetail;
        if (Math.abs(detail - current) > current * 0.02) {
            wantedDetail = detail; // the next selection takes it (ADR-147)
            selectionDirty = true;
            if (Math.abs(detail - loggedDetail) > loggedDetail * 0.1) {
                loggedDetail = detail;
                if (RenderSettings.performanceLogs()) {
                    Farfield.LOG.info("LOD detail: {} px per voxel, focal {} px (window height {}, projection m11 {}),"
                                    + " sections split within {} section sizes", RenderSettings.voxelPixels(), focal,
                            mc.getWindow().getHeight(), projection.m11(), String.format("%.1f", detail));
                }
            }
        }
    }

    private void select(ClientLevel level, Vec3 cam, org.joml.Vector3f look, long now) {
        long start = System.nanoTime();
        selectionTime = now;
        selectedX = cam.x;
        selectedY = cam.y;
        selectedZ = cam.z;
        selectedLookX = look.x;
        selectedLookY = look.y;
        selectedLookZ = look.z;
        selectionDirty = false;
        selections++;
        if (FrameProbe.enabled()) {
            FrameProbe.note("select");
        }
        drawList.clear();
        wanted.clear();
        // The selection runs on its own thread (ADR-143, ADR-147): its last result is drawn, and the next
        // one starts with what the render thread knows now.
        selectedPreview = RenderSettings.noisePreview();
        takePreviewSelection();
        if (previewJob == null) {
            startPreviewSelection(level, cam);
        }
        PreviewSelection p = previewResult;
        drawList.addAll(p.realDraw);
        wanted.addAll(p.realWant);
        maxFallbacks = Math.max(maxFallbacks, p.fallbacks);
        for (String reason : p.reasons) {
            if (fallbackReasons.size() < FALLBACK_REASONS) {
                fallbackReasons.add(reason);
            }
        }
        maxFinerFallbacks = Math.max(maxFinerFallbacks, p.finerFallbacks);
        // The preview stays under a level lock, locked the same way: on a server most of the
        // far view is preview, and without it the lock left only the stored terrain nearby.
        if (selectedPreview && p.preview) {
            drawList.addAll(p.draw);
            wanted.addAll(p.want);
        }
        // What the selection relied on to descend stays resident: the evictions ask these sets (ADR-147);
        // marking each of their tens of thousands of sections at every selection cost the render thread.
        reliedReal = p.realRelied;
        reliedPreview = p.relied;
        beginApply(cam, now);
        applyMinY = level.getMinBuildHeight();
        applyMaxY = level.getMaxBuildHeight();
        applyNanos = System.nanoTime() - start;
        continueApply(start + APPLY_BUDGET_NANOS);
    }

    private void beginApply(Vec3 cam, long now) {
        beginSplit(cam);
        nextDrawn.clear();
        applying = true;
        applyCursor = 0;
        applyTime = now;
        applyX = cam.x;
        applyY = cam.y;
        applyZ = cam.z;
        applyExtentSq = 0;
        applyNearestSq = Double.MAX_VALUE;
        applyOutdated = 0;
    }

    /** Applies the selection's draw list until {@code deadline}, then whole: the lists swapped in. */
    private void continueApply(long deadline) {
        long start = System.nanoTime();
        int n = drawList.size();
        int i = applyCursor;
        while (i < n) {
            long key = drawList.getLong(i++);
            int size = LodSection.SIZE << drawLevel(key);
            double x0 = LodKey.x(key) * (double) size - applyX;
            double y0 = LodKey.y(key) * (double) size - applyY;
            double z0 = LodKey.z(key) * (double) size - applyZ;
            double fx = Math.max(Math.abs(x0), Math.abs(x0 + size));
            double fy = Math.max(Math.abs(y0), Math.abs(y0 + size));
            double fz = Math.max(Math.abs(z0), Math.abs(z0 + size));
            // In 3D (ADR-141): far above the ground, the horizontal extent alone put the far plane short.
            applyExtentSq = Math.max(applyExtentSq, fx * fx + fy * fy + fz * fz);
            double nx = Math.max(0, Math.max(x0, -(x0 + size)));
            double ny = Math.max(0, Math.max(y0, -(y0 + size)));
            double nz = Math.max(0, Math.max(z0, -(z0 + size)));
            applyNearestSq = Math.min(applyNearestSq, nx * nx + ny * ny + nz * nz);
            Resident r = resident.get(key);
            if (r != null) {
                slotLastUsed[r.slot] = applyTime;
            }
            nextDrawn.add(key);
            splitForCulling(key, r, size);
            // A drawn copy older than the server's: fetched with the meshes this selection wants, not
            // after the whole background queue; the preview already hides itself for the newer data,
            // so the old copy can leave a hole where the new chunks are (ADR-074).
            if (stream != null && !PreviewLayer.isPreview(key) && stream.needsFetch(key)) {
                stale.add(key);
                applyOutdated++;
            }
            if ((i & 255) == 0 && System.nanoTime() > deadline) {
                break;
            }
        }
        applyCursor = i;
        applyNanos += System.nanoTime() - start;
        if (i >= n) {
            finishApply();
        }
    }

    private void finishApply() {
        long start = System.nanoTime();
        applying = false;
        maxOutdatedDrawn = Math.max(maxOutdatedDrawn, applyOutdated);
        LongOpenHashSet drawnBefore = drawn;
        drawn = nextDrawn;
        nextDrawn = drawnBefore;
        selectedMinY = applyMinY; // with drawn: the columns' tests walk the sections it holds
        selectedMaxY = applyMaxY;
        LongArrayList cpu = cpuList;
        cpuList = nextCpuList;
        nextCpuList = cpu;
        LongArrayList translucent = gpuTranslucent;
        gpuTranslucent = nextGpuTranslucent;
        nextGpuTranslucent = translucent;
        it.unimi.dsi.fastutil.ints.IntArrayList slots = gpuSlots;
        gpuSlots = nextGpuSlots;
        nextGpuSlots = slots;
        System.arraycopy(nextWhy, 0, cpuWhy, 0, cpuWhy.length);
        // Slots freed while the list was built: out of it, then free for new sections (no list names them now).
        if (!pendingFreeSlots.isEmpty()) {
            it.unimi.dsi.fastutil.ints.IntOpenHashSet freed = new it.unimi.dsi.fastutil.ints.IntOpenHashSet(pendingFreeSlots);
            gpuSlots.removeIf(freed::contains);
            freePendingSlots();
        }
        uploadCandidates();
        // The camera moves up to the reselection distance before the next selection.
        drawnExtent = Math.min(MAX_DISTANCE * 4, Math.max(lastSelectionDistance, Math.sqrt(applyExtentSq))
                + Math.sqrt(RESELECT_DISTANCE_SQ));
        drawnNearest = applyNearestSq == Double.MAX_VALUE ? 0
                : Math.max(0, Math.sqrt(applyNearestSq) - Math.sqrt(RESELECT_DISTANCE_SQ));
        if (FrameProbe.enabled()) {
            LongIterator added = drawn.iterator();
            while (added.hasNext()) {
                long k = added.nextLong();
                if (!drawnBefore.contains(k)) {
                    FrameProbe.note("+" + probeName(k));
                }
            }
            LongIterator removed = drawnBefore.iterator();
            while (removed.hasNext()) {
                long k = removed.nextLong();
                if (!drawn.contains(k)) {
                    FrameProbe.note("-" + probeName(k));
                }
            }
        }
        Vec3 cam = new Vec3(applyX, applyY, applyZ);
        long now = applyTime;
        // The traversal walks the grid from one corner: ask for what is near and in view first,
        // or the meshes in flight go to far sections behind the camera (ADR-056).
        wantOrder.clear();
        int inView = 0;
        for (int i = 0, n = wanted.size(); i < n; i++) {
            long key = wanted.getLong(i);
            boolean visible = inView(key, cam);
            inView += visible ? 1 : 0;
            wantOrder.add(key, WantOrder.score(nearestDistance(key, cam), visible ? 1 : -1));
        }
        LongIterator it = stale.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (!resident.containsKey(key)) {
                it.remove();
            } else {
                wantOrder.add(key, WantOrder.score(nearestDistance(key, cam), inView(key, cam) ? 1 : -1));
            }
        }
        wantOrder.sort();
        lastWantedInView = inView;
        if (stream != null) {
            stream.newSelection();
            stream.setDetail(selector.detail()); // the server pushes each level where it is drawn
        }
        wantCursor = 0;
        if (fillStart != 0) {
            for (int i = 0; i < FILL_CHECKS.length; i++) {
                if (fillTimeline[i] < 0 && now - fillStart >= FILL_CHECKS[i] * 1_000_000_000L) {
                    fillTimeline[i] = inView;
                }
            }
            if (inView > 0 || !requested.isEmpty()) {
                fillSettledSince = 0;
            } else if (fillSettledSince == 0) {
                fillSettledSince = now;
            }
        }
        appliedSelectionTime = applyTime;
        applyNanos += System.nanoTime() - start;
        lastSelectNanos = applyNanos;
    }

    /**
     * Starts a preview selection on its thread with what the render thread
     * knows now: the preview sections resident, the frustum, the camera and
     * the settings. Render thread, no preview selection running.
     */
    private void startPreviewSelection(ClientLevel level, Vec3 cam) {
        LongOpenHashSet ready = previewResident.clone(); // an array copy: walking the residents cost a millisecond
        LongOpenHashSet realReady = realResident.clone();
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap octants = realOctants.clone();
        boolean preview = selectedPreview;
        StreamClient streamAt = stream;
        LodWorld worldAt = world;
        FrustumIntersection view = new FrustumIntersection(new Matrix4f(cullMatrix));
        double cx = cam.x;
        double cy = cam.y;
        double cz = cam.z;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        double distance = lastSelectionDistance;
        int generation = previewGeneration;
        // The selectors' settings, set here while no selection runs (ADR-147).
        if (!Double.isNaN(wantedDetail)) {
            selector.setDetail(wantedDetail);
        }
        selector.setForcedLevel(wantedForcedLevel);
        LongPredicate viewed = key -> {
            int size = LodSection.SIZE << drawLevel(key);
            float x = (float) (LodKey.x(key) * (double) size - cx);
            float y = (float) (LodKey.y(key) * (double) size - cy);
            float z = (float) (LodKey.z(key) * (double) size - cz);
            return view.testAab(x, y, z, x + size, y + size, z + size);
        };
        selector.setOutsideView(viewed, outsideDetail);
        previewLayer.setDetail(selector.detail());
        previewLayer.setForcedLevel(wantedForcedLevel);
        previewLayer.setOutsideView(viewed, outsideDetail);
        previewJob = previewSelector.submit(() -> {
            long start = System.nanoTime();
            PreviewSelection p = new PreviewSelection(generation);
            p.preview = preview;
            if (clearExistence.getAndSet(false)) {
                existence.clear();
            }
            Long written;
            while ((written = existenceOps.poll()) != null) {
                existence.put(written.longValue(), EXISTS);
            }
            selector.select(cx, cy, cz, minY, maxY, distance, new LodSelector.Sections() {
                @Override
                public boolean exists(long key) {
                    return streamAt != null ? streamAt.exists(key) : sectionExists(worldAt, key);
                }

                @Override
                public boolean ready(long key) {
                    // A section the selection relies on to descend stays resident even when only its
                    // children are drawn: freed, its parent would fall back coarse until it is meshed again.
                    if (realReady.contains(key)) {
                        p.realRelied.add(key);
                        return true;
                    }
                    return false;
                }

                @Override
                public int octants(long key) {
                    return octants.get(key);
                }
            }, p.realDraw::add, p.realWant::add);
            p.fallbacks = selector.fallbacks();
            p.finerFallbacks = selector.finerFallbacks();
            p.reasons.addAll(selector.fallbackReasons());
            if (!preview) {
                p.nanos = System.nanoTime() - start;
                return p;
            }
            previewLayer.select(cx, cy, cz, minY, maxY, distance, key -> {
                if (ready.contains(key)) {
                    p.relied.add(key);
                    return true;
                }
                return false;
            }, p.draw::add, p.want::add);
            p.nanos = System.nanoTime() - start;
            return p;
        });
    }

    /**
     * Takes the preview selection that ended, if any: drawn from the next
     * selection on. Whether its result differs from the last one. Render thread.
     */
    private boolean takePreviewSelection() {
        if (previewJob == null || !previewJob.isDone()) {
            return false;
        }
        PreviewSelection p;
        try {
            p = previewJob.get();
        } catch (InterruptedException | java.util.concurrent.ExecutionException
                 | java.util.concurrent.CancellationException e) {
            previewJob = null;
            if (!previewFailureLogged) {
                previewFailureLogged = true;
                Farfield.LOG.warn("LOD preview selection failed, tried again at the next selection", e);
            }
            return false;
        }
        previewJob = null;
        if (p.generation != previewGeneration) {
            return false;
        }
        previewSelectNanos = Math.max(previewSelectNanos, p.nanos);
        boolean changed = p.fingerprint() != previewResult.fingerprint();
        previewResult = p;
        return changed;
    }

    /**
     * The selection's split between the GPU culling and the CPU loop
     * (ADR-146): a section goes to the GPU when its mesh shows and it lies
     * wholly outside the game's render distance and a margin of 4 chunks:
     * there it never gives way to the game's terrain, so its only cutout is
     * its own; the hand-over and the cutouts near the game's terrain stay on
     * the CPU. A section without quads, or not resident yet, is in neither
     * list: a mesh that comes asks for another selection.
     */
    private boolean splitGpu;
    /** Why sections stayed on the CPU at the last selection: off, no mesh (dropped), unused, looks, near the game. */
    private final int[] cpuWhy = new int[5];
    private final int[] nextWhy = new int[5];
    private double nearX0;
    private double nearX1;
    private double nearZ0;
    private double nearZ1;

    private void beginSplit(Vec3 cam) {
        java.util.Arrays.fill(nextWhy, 0);
        nextCpuList.clear();
        nextGpuSlots.clear();
        nextGpuTranslucent.clear();
        splitGpu = cullProgram != null && selector.forcedLevel() < 0;
        int reach = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 4) * 16;
        nearX0 = cam.x - reach;
        nearX1 = cam.x + reach;
        nearZ0 = cam.z - reach;
        nearZ1 = cam.z + reach;
    }

    /** One drawn section to the GPU or the CPU list (see the class of rules above), {@code r} its resident or null. */
    private void splitForCulling(long key, Resident r, int size) {
        if (r == null || r.quadCount == 0) {
            nextWhy[1]++;
            return; // nothing to draw: a mesh that comes asks for another selection
        }
        if (!splitGpu || !looksReady(r)) {
            nextWhy[!splitGpu ? 0 : 3]++;
            nextCpuList.add(key);
            return;
        }
        double x0 = LodKey.x(key) * (double) size;
        double z0 = LodKey.z(key) * (double) size;
        if (x0 < nearX1 && x0 + size > nearX0 && z0 < nearZ1 && z0 + size > nearZ0) {
            nextWhy[4]++;
            nextCpuList.add(key);
            return;
        }
        nextGpuSlots.add(r.slot);
        if (r.groups[PLANT_GROUP] > r.opaqueCount) {
            nextGpuTranslucent.add(key);
        }
    }

    /** Uploads the GPU's slots of this selection and sizes its command lists. */
    private void uploadCandidates() {
        gpuCandidates = gpuSlots.size();
        if (gpuCandidates == 0) {
            return;
        }
        long bytes = (long) gpuCandidates * 4;
        if (bytes > cullCandidates.size()) {
            cullCandidates.grow(Math.max(bytes, cullCandidates.size() * 2), 0);
        }
        cullCandidates.upload(0, gpuSlots.toIntArray());
        // Seven commands at most per section and list: six runs of directions and the plants'.
        gpuMaxCommands = gpuCandidates * 8;
        long commandBytes = 3L * gpuMaxCommands * COMMAND_BYTES;
        if (commandBytes > gpuCommands.size()) {
            gpuCommands.grow(Math.max(commandBytes, gpuCommands.size() * 2), 0);
        }
    }

    /**
     * The GPU culling pass of this frame (ADR-146): writes the far sections'
     * commands and their counts, read by the draws after a command barrier.
     */
    private void cullOnGpu(Vec3 cam) {
        if (gpuCandidates == 0 || cullProgram == null) {
            return;
        }
        for (int p = 0; p < 6; p++) {
            cullMatrix.frustumPlane(p, plane);
            planes[p * 4] = plane.x;
            planes[p * 4 + 1] = plane.y;
            planes[p * 4 + 2] = plane.z;
            planes[p * 4 + 3] = plane.w;
        }
        int blockX = (int) Math.floor(cam.x);
        int blockY = (int) Math.floor(cam.y);
        int blockZ = (int) Math.floor(cam.z);
        glClearNamedBufferData(gpuCounts.id(), GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        GlStateManager._glUseProgram(cullProgram.id());
        glUniform1i(cullCountU, gpuCandidates);
        glUniform1i(cullMaxU, gpuMaxCommands);
        glUniform4fv(cullPlanesU, planes);
        glUniform3i(cullCameraBlockU, blockX, blockY, blockZ);
        glUniform3f(cullCameraFracU, (float) (cam.x - blockX), (float) (cam.y - blockY), (float) (cam.z - blockZ));
        glUniform1i(cullPlantsU, RenderSettings.drawPlants() ? 1 : 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, cullGroups.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, sectionInfo.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, cullCandidates.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, gpuCommands.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, gpuCounts.id());
        glDispatchCompute((gpuCandidates + 63) / 64, 1, 1);
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        GlStateManager._glUseProgram(0);
    }

    /** Draws a GPU-culled list (0 solid, 1 cutout, 2 plants) with its count from the culling pass (ADR-146). */
    private void drawCulled(int list) {
        if (gpuCandidates == 0 || cullProgram == null) {
            return;
        }
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, gpuCommands.id());
        glBindBuffer(GL_PARAMETER_BUFFER, gpuCounts.id());
        glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_INT, (long) list * gpuMaxCommands * COMMAND_BYTES,
                (long) list * 4, gpuMaxCommands, 0);
        glBindBuffer(GL_PARAMETER_BUFFER, 0);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect.id());
    }

    /** Requests the meshes of {@link #wantOrder} in its order, while there is room in flight. */
    private void requestWanted() {
        while (wantCursor < wantOrder.size() && requested.size() < MAX_IN_FLIGHT) {
            long key = wantOrder.key(wantCursor++);
            if (requested.contains(key)) {
                continue; // a stale section in flight stays stale: its data changed after the job read it
            }
            if (stream != null && stream.needsFetch(key)) {
                // From the server first; an older copy in the mirror is drawn meanwhile.
                stream.want(key);
                if (!stream.hasLocal(key) || resident.containsKey(key)) {
                    continue;
                }
            }
            if (stream != null && stream.storing(key)) {
                // Its version is held, its data not stored yet: a mesh now reads the old data, then
                // waits stale for a second one, the preview held over it (ADR-086). Asked once stored.
                waitingStore.add(key);
                continue;
            }
            if (stale.remove(key) || !resident.containsKey(key)) {
                request(key);
            }
        }
    }

    /** Starts timing how long the view takes to fill in, for the fill line ({@link #logFill}). */
    private void startFill(long now, String cause) {
        if (fillStart != 0) {
            logFill(now, true);
        }
        fillStart = now;
        fillCause = cause;
        java.util.Arrays.fill(fillTimeline, -1);
        fillMeshes = 0;
        fillSettledSince = 0;
    }

    /**
     * Logs the sections missing in view at each of {@link #FILL_CHECKS} since
     * a join or a teleport, once the view stayed complete for
     * {@link #FILL_SETTLED_NANOS}, else after {@link #FILL_WINDOW_NANOS} (or at
     * the next join or teleport, {@code cut}). Preview tiles built later add
     * sections, so the count can rise again; the noise preview logs its own fill.
     */
    private void logFill(long now, boolean cut) {
        boolean settled = fillSettledSince != 0 && now - fillSettledSince >= FILL_SETTLED_NANOS;
        if (fillStart == 0 || !cut && !settled && now - fillStart < FILL_WINDOW_NANOS) {
            return;
        }
        StringBuilder timeline = new StringBuilder();
        for (int i = 0; i < FILL_CHECKS.length && fillTimeline[i] >= 0; i++) {
            timeline.append(i == 0 ? "" : ", ").append(FILL_CHECKS[i]).append(" s ").append(fillTimeline[i]);
        }
        // Settled: nothing missing in view and no mesh in flight, out of view included.
        String settledAt = fillSettledSince == 0 ? "not settled"
                : String.format(java.util.Locale.ROOT, "settled at %.1f s", (fillSettledSince - fillStart) / 1e9);
        if (RenderSettings.performanceLogs()) {
            Farfield.LOG.info("LOD view fill after {}: sections missing in view at {}; view {}; {} meshes uploaded in {} s",
                    fillCause, timeline, settledAt, fillMeshes,
                    (Math.min(now, fillStart + FILL_WINDOW_NANOS) - fillStart) / 1_000_000_000L);
        }
        fillStart = 0;
    }

    /**
     * Whether the real terrain the last selection draws over a chunk column
     * draws it: some drawn real section has quads in that column, and none
     * covering it without quads there waits for a new mesh (it may be the
     * one that gets this chunk's terrain). The preview hides itself over new real chunks only then
     * (ADR-076, ADR-077).
     */
    private boolean realReady(int chunkX, int chunkZ) {
        boolean covered = false;
        for (int level = 0; level < LodWorld.LEVELS; level++) {
            int size = LodSection.SIZE << level;
            int x = chunkX >> (level + 1);
            int z = chunkZ >> (level + 1);
            for (int y = Math.floorDiv(selectedMinY, size); y <= Math.floorDiv(selectedMaxY - 1, size); y++) {
                long key = LodKey.pack(level, x, y, z);
                if (drawn.contains(key)) {
                    boolean draws = drawsColumn(resident.get(key), level, chunkX, chunkZ);
                    // Waiting for a new mesh holds the preview only where the mesh drawn has nothing
                    // in this column yet: at a generation front, sections already drawing it are meshed
                    // again for each new chunk beside, and the preview stayed over drawn terrain
                    // (ADR-087).
                    if (!draws && (stale.contains(key) || requested.contains(key))) {
                        return false;
                    }
                    covered |= draws;
                }
            }
        }
        return covered;
    }

    /**
     * Counts the holes around the last selection's camera, times how long
     * each lasted, and logs a few new ones with what every level knows there.
     */
    private void scanHoles(long now, int renderDistance) {
        int cx0 = (int) Math.floor(selectedX) >> 4;
        int cz0 = (int) Math.floor(selectedZ) >> 4;
        int r = HOLE_RADIUS_CHUNKS;
        int count = 0;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r || Math.max(Math.abs(dx), Math.abs(dz)) <= renderDistance) {
                    continue; // the game's own chunks: its renderer draws them
                }
                int cx = cx0 + dx;
                int cz = cz0 + dz;
                if (columnDrawn(cx, cz)) {
                    continue;
                }
                long chunk = (long) cx << 32 | (cz & 0xFFFFFFFFL);
                seen.add(chunk);
                count++;
                if (!holeSince.containsKey(chunk)) {
                    holeSince.put(chunk, now);
                    if (holeExamples < HOLE_EXAMPLES) {
                        holeExamples++;
                        Farfield.LOG.info("LOD hole at chunk {} {} ({} blocks away), nothing drawn there:\n{}", cx, cz,
                                (int) Math.sqrt(d2) * 16, describeColumn(cx, cz));
                    }
                }
            }
        }
        var it = holeSince.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            long chunk = e.getLongKey();
            if (seen.contains(chunk)) {
                continue;
            }
            int dx = (int) (chunk >> 32) - cx0;
            int dz = (int) chunk - cz0;
            if (dx * dx + dz * dz <= r * r && Math.max(Math.abs(dx), Math.abs(dz)) > renderDistance) {
                // Filled in where it was looked for; one the camera left behind is not timed.
                long lasted = now - e.getLongValue();
                holesClosed++;
                holeNanos += lasted;
                holeMaxNanos = Math.max(holeMaxNanos, lasted);
            }
            it.remove();
        }
        maxHoles = Math.max(maxHoles, count);
    }

    /** What every level, real and preview, knows of a chunk column, for the hole detector. */
    private String describeColumn(int chunkX, int chunkZ) {
        StringBuilder out = new StringBuilder();
        for (int level = 0; level < LodWorld.LEVELS; level++) {
            int size = LodSection.SIZE << level;
            int x = chunkX >> (level + 1);
            int z = chunkZ >> (level + 1);
            out.append("  level ").append(level).append(':');
            boolean any = false;
            for (int y = Math.floorDiv(selectedMinY, size); y <= Math.floorDiv(selectedMaxY - 1, size); y++) {
                long key = LodKey.pack(level, x, y, z);
                long preview = PreviewLayer.toPreview(key);
                boolean exists = stream != null ? stream.exists(key) : storeHas(key);
                if (!exists && !resident.containsKey(key) && !resident.containsKey(preview) && !drawn.contains(preview)) {
                    continue;
                }
                any = true;
                out.append(" [y ").append(y).append(exists ? " real" : " no real");
                appendState(out, key, level, chunkX, chunkZ);
                if (resident.containsKey(preview) || drawn.contains(preview)) {
                    out.append("; preview");
                    appendState(out, preview, level, chunkX, chunkZ);
                }
                if (stream != null && exists) {
                    out.append("; ").append(stream.describe(key));
                }
                out.append(']');
            }
            out.append(any ? "" : " nothing").append('\n');
        }
        out.append("  preview chunk: ").append(previewLayer.describeChunk(chunkX, chunkZ));
        return out.toString();
    }

    private void appendState(StringBuilder out, long key, int level, int chunkX, int chunkZ) {
        Resident r = resident.get(key);
        out.append(drawn.contains(key) ? ", drawn" : "");
        if (r == null) {
            out.append(", not meshed");
        } else {
            out.append(", ").append(r.quadCount).append(" quads")
                    .append(drawsColumn(r, level, chunkX, chunkZ) ? ", some here" : ", none here");
        }
        out.append(stale.contains(key) ? ", stale" : "").append(requested.contains(key) ? ", meshing" : "");
    }

    /** Whether every material of a resident section has its look, so it is drawn. */
    private boolean looksReady(Resident r) {
        int version = colors.version();
        if (r.looksVersion != version) {
            r.looksVersion = version;
            boolean ready = true;
            for (int m : r.materials) {
                if (!colors.built(m)) {
                    ready = false;
                    break;
                }
            }
            r.looksReady = ready;
        }
        return r.looksReady;
    }

    /** The coarsest draw level whose sections keep their chunk columns (8 KB a section at most). */
    private static final int MAX_COLUMNS_LEVEL = 7;

    /** Whether a resident section of draw level {@code level} has quads in a chunk column it draws. */
    private boolean drawsColumn(Resident r, int level, int chunkX, int chunkZ) {
        if (level > MAX_COLUMNS_LEVEL || r == null || r.columns == null || !looksReady(r)) {
            return false;
        }
        int side = 2 << level;
        int bit = (chunkZ & (side - 1)) * side + (chunkX & (side - 1));
        return (r.columns[bit >> 6] >>> bit & 1) != 0;
    }

    /**
     * Whether any section the last selection draws, real or preview, has
     * quads in a chunk column: one without is a hole, the sky through the
     * terrain.
     */
    private boolean columnDrawn(int chunkX, int chunkZ) {
        for (int level = 0; level < LodWorld.LEVELS; level++) {
            int size = LodSection.SIZE << level;
            int x = chunkX >> (level + 1);
            int z = chunkZ >> (level + 1);
            for (int y = Math.floorDiv(selectedMinY, size); y <= Math.floorDiv(selectedMaxY - 1, size); y++) {
                long key = LodKey.pack(level, x, y, z);
                if (drawn.contains(key) && drawsColumn(resident.get(key), level, chunkX, chunkZ)) {
                    return true;
                }
                long preview = PreviewLayer.toPreview(key);
                if (drawn.contains(preview) && drawsColumn(resident.get(preview), level, chunkX, chunkZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Chunk columns a real mesh just began to draw while the preview showed
     * them, with the time it came: until the preview's own meshes leave them,
     * both drew there, and two sea tops blended into a lighter band for the
     * frame or two the preview took to be meshed again (ADR-100).
     */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap handOver =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /** Of those, the ones the real terrain drew once: hidden from the preview until it leaves them. */
    private final LongOpenHashSet hidingPreview = new LongOpenHashSet();
    /** The columns hidden from the preview this frame. */
    private final LongOpenHashSet hiddenColumns = new LongOpenHashSet();
    /** Preview sections (level, x, 0, z) over a hidden column: drawn with the discarding shader. */
    private final LongOpenHashSet previewOverHidden = new LongOpenHashSet();
    /** The grid of hidden columns the shader reads: this many chunks a side, centered on the camera. */
    private static final int HIDDEN_SIDE = 256;
    /** A column is handed over for this long at most: the preview's new meshes come well before. */
    private static final long HAND_OVER_NANOS = 5_000_000_000L;
    private final int[] hiddenBits = new int[HIDDEN_SIDE * HIDDEN_SIDE / 32];
    private int hiddenMinX;
    private int hiddenMinZ;
    private boolean hiddenUploaded;
    /** Columns handed over since the last report, and those still under the preview at the time limit. */
    private long handedOver;
    private long handOverExpired;

    /** Notes the chunk columns a real section's new mesh draws that its old one did not. */
    private void noteHandOver(long key, long[] before, long[] after) {
        if (after == null) {
            return;
        }
        int side = 2 << LodKey.level(key);
        int baseX = LodKey.x(key) * side;
        int baseZ = LodKey.z(key) * side;
        long now = System.nanoTime();
        for (int i = 0; i < after.length; i++) {
            long added = before == null || i >= before.length ? after[i] : after[i] & ~before[i];
            while (added != 0) {
                int bit = i * 64 + Long.numberOfTrailingZeros(added);
                added &= added - 1;
                int chunkX = baseX + bit % side;
                int chunkZ = baseZ + bit / side;
                handOver.putIfAbsent((long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL), now);
            }
        }
    }

    /**
     * After the selection: a handed-over column is hidden from the preview
     * in this frame's shader once a drawn real section with quads there
     * spans the height of the preview's top there (its sea, its ground), and
     * stays hidden until the preview's meshes no longer draw there (shown
     * again for a frame, the preview flickered over the real sea). Not
     * {@link #realReady}: it waits for every section over the column, and the
     * overlap lasted as long; nor any real quads there: a section under the
     * sea left holes where the preview's sea was hidden. Render thread.
     */
    private void updateHandOver(Vec3 cam, long now) {
        hiddenColumns.clear();
        previewOverHidden.clear();
        var it = handOver.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            long c = e.getLongKey();
            int chunkX = (int) (c >> 32);
            int chunkZ = (int) c;
            long top = previewTop(chunkX, chunkZ);
            if (top == Long.MIN_VALUE) {
                it.remove(); // the preview's new meshes left it, or it never drew there
                hidingPreview.remove(c);
                continue;
            }
            if (now - e.getLongValue() > HAND_OVER_NANOS) {
                handOverExpired++;
                it.remove();
                hidingPreview.remove(c);
                continue;
            }
            if (hidingPreview.contains(c) || realCovers(chunkX, chunkZ, (int) (top >> 32), (int) top)) {
                hidingPreview.add(c);
                hiddenColumns.add(c);
                for (int level = 0; level < LodWorld.LEVELS; level++) {
                    previewOverHidden.add(LodKey.pack(level, chunkX >> (level + 1), 0, chunkZ >> (level + 1)));
                }
            }
        }
        handedOver += hiddenColumns.size();
        if (hiddenColumns.isEmpty() && !hiddenUploaded) {
            return;
        }
        hiddenMinX = ((int) Math.floor(cam.x) >> 4) - HIDDEN_SIDE / 2;
        hiddenMinZ = ((int) Math.floor(cam.z) >> 4) - HIDDEN_SIDE / 2;
        java.util.Arrays.fill(hiddenBits, 0);
        LongIterator columns = hiddenColumns.iterator();
        while (columns.hasNext()) {
            long c = columns.nextLong();
            int gx = (int) (c >> 32) - hiddenMinX;
            int gz = (int) c - hiddenMinZ;
            if (gx >= 0 && gz >= 0 && gx < HIDDEN_SIDE && gz < HIDDEN_SIDE) {
                int bit = gz * HIDDEN_SIDE + gx;
                hiddenBits[bit >> 5] |= 1 << (bit & 31);
            }
        }
        previewHidden.upload(0, hiddenBits);
        hiddenUploaded = !hiddenColumns.isEmpty();
    }

    /** Whether a drawn real section with quads in a chunk column spans blocks [minY, maxY). */
    private boolean realCovers(int chunkX, int chunkZ, int minY, int maxY) {
        for (int level = 0; level < LodWorld.LEVELS; level++) {
            int size = LodSection.SIZE << level;
            int x = chunkX >> (level + 1);
            int z = chunkZ >> (level + 1);
            for (int y = Math.floorDiv(minY, size); y <= Math.floorDiv(maxY - 1, size); y++) {
                long key = LodKey.pack(level, x, y, z);
                if (drawn.contains(key) && drawsColumn(resident.get(key), level, chunkX, chunkZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The blocks [minY, maxY) of the highest drawn preview section with quads
     * in a chunk column, packed {@code minY << 32 | maxY}; Long.MIN_VALUE for none.
     */
    private long previewTop(int chunkX, int chunkZ) {
        int bestMin = Integer.MIN_VALUE;
        int bestMax = Integer.MIN_VALUE;
        for (int level = 0; level < LodWorld.LEVELS; level++) {
            int size = LodSection.SIZE << level;
            int x = chunkX >> (level + 1);
            int z = chunkZ >> (level + 1);
            for (int y = Math.floorDiv(selectedMaxY - 1, size); y >= Math.floorDiv(selectedMinY, size); y--) {
                long p = PreviewLayer.toPreview(LodKey.pack(level, x, y, z));
                if (drawn.contains(p) && drawsColumn(resident.get(p), level, chunkX, chunkZ)) {
                    if ((y + 1) * size > bestMax) {
                        bestMin = y * size;
                        bestMax = (y + 1) * size;
                    }
                    break; // the highest at this level
                }
            }
        }
        return bestMax == Integer.MIN_VALUE ? Long.MIN_VALUE : (long) bestMin << 32 | (bestMax & 0xFFFFFFFFL);
    }

    /** A preview section over a hidden column: its fragments there are discarded. */
    private boolean previewOverHandOver(long key) {
        return !previewOverHidden.isEmpty() && PreviewLayer.isPreview(key)
                && previewOverHidden.contains(LodKey.pack(drawLevel(key), LodKey.x(key), 0, LodKey.z(key)));
    }

    /**
     * With the frame probe on (ADR-099): what draws each chunk column the hole
     * detector watches, this frame, for its flicker watch.
     */
    private void watchColumns(int renderDistance) {
        io.lakio.farfield.core.debug.ColumnFlicker watch = FrameProbe.beginColumns();
        if (watch == null) {
            return;
        }
        int cx0 = (int) Math.floor(selectedX) >> 4;
        int cz0 = (int) Math.floor(selectedZ) >> 4;
        int r = HOLE_RADIUS_CHUNKS;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r || Math.max(Math.abs(dx), Math.abs(dz)) <= renderDistance) {
                    continue; // the game's own chunks
                }
                watch.set(cx0 + dx, cz0 + dz, columnState(cx0 + dx, cz0 + dz));
            }
        }
        FrameProbe.endColumns();
    }

    /**
     * The finest real level with quads in a chunk column, else the finest
     * preview level, else nothing; a real level with preview quads in the
     * same column is both drawn over each other.
     */
    private int columnState(int chunkX, int chunkZ) {
        int none = io.lakio.farfield.core.debug.ColumnFlicker.NONE;
        int real = none;
        int preview = none;
        for (int level = 0; level < LodWorld.LEVELS; level++) {
            int size = LodSection.SIZE << level;
            int x = chunkX >> (level + 1);
            int z = chunkZ >> (level + 1);
            for (int y = Math.floorDiv(selectedMinY, size); y <= Math.floorDiv(selectedMaxY - 1, size); y++) {
                long key = LodKey.pack(level, x, y, z);
                if (real == none && drawn.contains(key) && drawsColumn(resident.get(key), level, chunkX, chunkZ)) {
                    real = level;
                }
                if (preview == none) {
                    long p = PreviewLayer.toPreview(key);
                    if (drawn.contains(p) && drawsColumn(resident.get(p), level, chunkX, chunkZ)) {
                        preview = io.lakio.farfield.core.debug.ColumnFlicker.preview(level);
                    }
                }
            }
        }
        if (real == none) {
            return preview;
        }
        return preview == none ? io.lakio.farfield.core.debug.ColumnFlicker.real(real)
                : io.lakio.farfield.core.debug.ColumnFlicker.both(real);
    }

    /** A section for the frame probe's notes: R (real) or P (preview), its level, [x,y,z]. */
    private static String probeName(long key) {
        return (PreviewLayer.isPreview(key) ? "P" : "R") + drawLevel(key) + "[" + LodKey.x(key) + "," + LodKey.y(key)
                + "," + LodKey.z(key) + "]";
    }

    /** The level a section is drawn at (preview keys carry an offset). */
    private static int drawLevel(long key) {
        return PreviewLayer.drawLevel(key);
    }

    /** Whether the store holds a real section, read directly (debug lines). Render thread. */
    private boolean storeHas(long key) {
        try {
            return world != null && world.exists(key);
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether the store holds a real section; the selection's thread, its cache (ADR-147). */
    private boolean sectionExists(LodWorld w, long key) {
        if (w == null) {
            return false;
        }
        byte known = existence.get(key);
        if (known != 0) {
            return known == EXISTS;
        }
        boolean exists;
        try {
            exists = w.exists(key);
        } catch (IOException e) {
            exists = false;
        }
        existence.put(key, exists ? EXISTS : ABSENT);
        return exists;
    }

    private void logStats(int count) {
        nextStats = System.nanoTime() + STATS_INTERVAL_NANOS;
        int jobs = meshJobs.getAndSet(0);
        long jobNanos = meshNanos.getAndSet(0);
        long readNanos = meshReadNanos.getAndSet(0);
        long cpuNanos = meshCpuNanos.getAndSet(0);
        long waitNanos = meshWaitNanos.getAndSet(0);
        long[] cacheCounts = sectionCache.takeCounts();
        if (jobs > 0) {
            if (RenderSettings.performanceLogs()) {
                Farfield.LOG.info("LOD meshing: {} jobs on {} threads, {} us per job ({} us on the processor), {} us"
                                + " of it reading sections, {} ms waiting to start on average, {} % of {} section"
                                + " reads from the cache", jobs, meshers.getCorePoolSize(), jobNanos / 1000 / jobs,
                        cpuNanos / 1000 / jobs, readNanos / 1000 / jobs, waitNanos / 1_000_000 / jobs,
                        cacheCounts[1] == 0 ? 0 : cacheCounts[0] * 100 / cacheCounts[1], cacheCounts[1]);
            }
        }
        if (RenderSettings.performanceLogs()) {
            Farfield.LOG.info("LOD renderer: {} sections drawn, {} resident, {} MB of {} MB quads used, {} meshing,"
                            + " {} meshes uploaded and {} freed, {} selections since last line; per frame {} us on the"
                            + " render thread and {} us on the GPU (depth pre-pass {}, opaque {}, plants {}, translucent {}),"
                            + " {} of {} quads sent (face directions), {} of them plants, {} sections may discard, {} left"
                            + " to the game, over {}"
                            + " frames; detail {}; at most {} coarse fallbacks, {} finer fallbacks and {} outdated"
                            + " sections drawn per selection; the longest LOD frame {} us ({}), the longest frame {} ms, {}"
                            + " frames over 50 ms; the longest preview selection {} ms on its thread; steps per frame {};"
                            + " {} sections culled on the GPU, {} on the CPU {}",
                    count, resident.size(),
                    arena.used() * 8 >> 20, arena.capacity() * 8 >> 20, requested.size(), meshesUploaded, meshesEvicted,
                    selections, frameNanos / 1000 / Math.max(1, timedFrames),
                    (gpuPassNanos[0] + gpuPassNanos[1] + gpuPassNanos[2] + gpuPassNanos[3]) / 1000 / Math.max(1, gpuFrames),
                    RenderSettings.depthPrepass() ? gpuPassNanos[0] / 1000 / Math.max(1, gpuFrames) + " us" : "off",
                    gpuPassNanos[1] / 1000 / Math.max(1, gpuFrames),
                    RenderSettings.drawPlants() ? gpuPassNanos[2] / 1000 / Math.max(1, gpuFrames) : "off",
                    gpuPassNanos[3] / 1000 / Math.max(1, gpuFrames),
                    quadsSubmitted / Math.max(1, timedFrames), quadsInView / Math.max(1, timedFrames),
                    plantQuadsSubmitted / Math.max(1, timedFrames),
                    discardSections / Math.max(1, timedFrames), leftToGame / Math.max(1, timedFrames), timedFrames,
                    String.format("%.1f", selector.detail()), maxFallbacks, maxFinerFallbacks, maxOutdatedDrawn,
                    maxFrameNanos / 1000, worstFrame, maxFrameGapNanos / 1_000_000, longFrames,
                    previewSelectNanos / 1_000_000, stepMeans(timedFrames), gpuCandidates, cpuList.size(), java.util.Arrays.toString(cpuWhy));
        }
        if (RenderSettings.performanceLogs()) {
            Farfield.LOG.info("LOD holes: at most {} chunk columns without anything drawn within {} chunks;"
                            + " {} closed in 10 s, {} ms on average, {} ms at most; {} open now; {}", maxHoles,
                    HOLE_RADIUS_CHUNKS, holesClosed, holesClosed == 0 ? 0 : holeNanos / holesClosed / 1_000_000,
                    holeMaxNanos / 1_000_000, holeSince.size(), previewLayer.heldReport()
                            + "; " + swapsDone + " preview swaps put in whole, " + swapsTimedOut + " at the "
                            + SWAP_TIMEOUT_NANOS / 1_000_000 + " ms limit, " + swapMaxNanos / 1_000_000 + " ms at most"
                            + "; preview hidden over " + handedOver + " handed-over column frames, "
                            + handOverExpired + " columns still under it after " + HAND_OVER_NANOS / 1_000_000_000L + " s");
        } else {
            previewLayer.heldReport();
        }
        maxHoles = 0;
        handedOver = 0;
        handOverExpired = 0;
        swapsDone = 0;
        swapsTimedOut = 0;
        swapMaxNanos = 0;
        holesClosed = 0;
        holeNanos = 0;
        holeMaxNanos = 0;
        holeExamples = 0;
        if (RenderSettings.performanceLogs() && !fallbackReasons.isEmpty()) {
            Farfield.LOG.info("LOD coarse fallbacks, a few: {}", String.join("; ", fallbackReasons));
        }
        fallbackReasons.clear();
        maxFallbacks = 0;
        maxFinerFallbacks = 0;
        maxOutdatedDrawn = 0;
        quadsInView = 0;
        quadsSubmitted = 0;
        plantQuadsSubmitted = 0;
        discardSections = 0;
        leftToGame = 0;
        frameNanos = 0;
        maxFrameNanos = 0;
        java.util.Arrays.fill(stepNanos, 0);
        previewSelectNanos = 0;
        maxFrameGapNanos = 0;
        longFrames = 0;
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
        previewResident.clear();
        realResident.clear();
        realOctants.clear();
        blendDirty.clear();
        requested.clear();
        rewrittenWhileMeshing.clear();
        waitingStore.clear();
        for (Swap swap : swaps.values()) {
            for (Staged st : swap.ready) {
                unstage(st);
            }
        }
        swaps.clear();
        swapOf.clear();
        stale.clear();
        provisional.clear();
        clearExistence.set(true); // the selection's cache (ADR-147)
        existenceOps.clear();
        sectionCache.clear();
        drawList.clear();
        wanted.clear();
        wantOrder.clear();
        wantCursor = 0;
        lastCamX = Double.NaN; // the first frame of a world is a join, not a teleport
        done.clear();
        rewritten.clear();
        neighborWrites.clear();
        colors.reset();
        tints.reset();
        freeSlotCount = 0;
        slotCount = 0;
        pendingFreeSlots.clear();
        gpuCandidates = 0;
        cpuList.clear();
        gpuTranslucent.clear();
        gpuSlots.clear();
        drawn.clear();
        applying = false;
        appliedSelectionTime = 0;
        selectionDirty = true;
        world = next;
        previewGeneration++;
        previewJob = null;
        previewResult = new PreviewSelection(previewGeneration);
        previewLayer.attach(next);
        if (world != null) {
            world.addListener(listener);
            Farfield.LOG.info("LOD renderer attached to a LOD store");
            startFill(System.nanoTime(), "joining");
        }
    }

    private void request(long key) {
        if (requested.size() >= MAX_IN_FLIGHT || !requested.add(key)) {
            return;
        }
        LodWorld w = world;
        int gen = generation.get();
        long queued = System.nanoTime();
        meshers.execute(() -> {
            MeshResult result;
            long start = System.nanoTime();
            meshWaitNanos.addAndGet(start - queued);
            long cpuStart = cpuTime();
            try {
                LodSection section = PreviewLayer.isPreview(key) ? previewLayer.build(w, key) : readSection(w, key);
                result = section == null ? emptyMesh(gen, key) : mesh(w, gen, key, section);
            } catch (IOException | RuntimeException e) {
                Farfield.LOG.debug("LOD mesh of {} failed", LodKey.toString(key), e);
                result = emptyMesh(gen, key);
            }
            meshNanos.addAndGet(System.nanoTime() - start);
            meshCpuNanos.addAndGet(cpuTime() - cpuStart);
            meshJobs.incrementAndGet();
            done.add(result);
        });
    }

    /** The calling thread's processor time in nanoseconds, 0 when the JVM does not measure it. */
    private static long cpuTime() {
        return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : 0;
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
        MaterialClassifier classes = drawLevel(key) == 0 ? materials : new MaterialClassifier() {
            @Override
            public int classOf(int m) {
                int c = materials.classOf(m);
                return c == MaterialClassifier.PARTIAL || c == MaterialClassifier.SHAPED ? MaterialClassifier.OPAQUE
                        : c == MaterialClassifier.PLANT ? MaterialClassifier.AIR : c;
            }

            @Override
            public boolean isFluid(int m) {
                return materials.isFluid(m);
            }

            @Override
            public int fluidOf(int m) {
                return materials.fluidOf(m); // kelp is its water there, without its quads
            }
        };
        boolean translucent = hasTranslucent(section, classes);
        int[][] solid = translucent ? new int[6][] : null;
        byte[][] light = new byte[6][];
        if (PreviewLayer.isPreview(key)) {
            // Its neighbors are built from the tiles: their solid layers for water faces, and
            // their light for every face on a border (else a sea floor face there took full
            // light, white bands through the preview's sea).
            int[][] planes = previewLayer.solidNeighbors(w, key, classes, borderPlanes.get(), light);
            solid = translucent ? planes : null;
        } else {
            readNeighbors(w, key, classes, solid, light);
        }
        MeshBuffer out = meshBuffer.get();
        // Stored terrain reads missing chunks as air: no water walls into them.
        mesher.get().setEmptyColumnsUnknown(!PreviewLayer.isPreview(key));
        // Coarse water keeps its highest block's light, stored or preview: faces in it see less.
        mesher.get().setVoxelBlocks(1 << drawLevel(key));
        mesher.get().mesh(section, classes, tintedMaterials, null, solid, light, out);
        // Masked preview columns only hide faces: their own quads are dropped.
        boolean preview = PreviewLayer.isPreview(key);
        int dropped = preview ? PreviewLayer.maskGround(w) : -1;
        int droppedFluid = preview ? PreviewLayer.maskFluid(w) : -1;
        int[] groups = new int[GROUPS + 1];
        for (int d = 0; d <= MeshBuffer.PLANTS; d++) {
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
        for (int d = 0; d <= MeshBuffer.PLANTS; d++) {
            long[] quads = out.quads(d);
            for (int i = 0, c = out.count(d); i < c; i++) {
                long q = quads[i];
                int m = Quad.material(q);
                if (m != dropped && m != droppedFluid) {
                    int g = group(classes, m, d);
                    all[next[g]++] = q;
                    if (g == PLANT_GROUP) {
                        // Plants go to the cutout pass on their own; the section's faces keep theirs.
                        provisional |= !colors.built(m);
                    } else if (g < Quad.DIRECTIONS) {
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
        int maxMaterial = -1;
        it.unimi.dsi.fastutil.ints.IntOpenHashSet used = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        int octants = 0;
        int level = drawLevel(key);
        // The chunk columns a section draws, for the hole detector near the player: none kept above
        // level 7 (E.3d), where a section spans up to 65,536 chunks a side and the detector never looks.
        long[] drawnColumns = all.length == 0 || level > MAX_COLUMNS_LEVEL ? null
                : new long[Math.max(1, (4 << (2 * level)) >> 6)];
        for (long q : all) {
            maxMaterial = Math.max(maxMaterial, Quad.material(q));
            used.add(Quad.material(q));
            if (octants != 0xFF) {
                octants |= Quad.octants(q);
            }
            if (drawnColumns != null) {
                Quad.markColumns(q, 4 - level, drawnColumns);
            }
        }
        long[] borders = PreviewLayer.isPreview(key) ? null : borderHashes(section, classes, level);
        int[] usedMaterials = used.toIntArray();
        for (int m : usedMaterials) {
            colors.want(m); // the looks of what is meshed, nearest first, go before the others (ADR-078)
        }
        return new MeshResult(gen, key, all, groups, cutout, provisional, occupied, packed, maxMaterial,
                usedMaterials, mesher.get().borderFaces(), octants, drawnColumns, borders);
    }

    /** @param list a face direction, or {@link MeshBuffer#PLANTS} */
    private static int group(MaterialClassifier classes, int material, int list) {
        if (list == MeshBuffer.PLANTS) {
            return PLANT_GROUP;
        }
        return classes.classOf(material) == MaterialClassifier.TRANSLUCENT ? Quad.DIRECTIONS + list : list;
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
                light[d] = BinaryGreedyMesher.borderLight(section, d, lights[d], classes, 1 << level);
                if (solid != null) {
                    solid[d] = BinaryGreedyMesher.borderPlane(section, d, classes, true, planes[d]);
                    BinaryGreedyMesher.addEmptyColumns(section, d, classes, solid[d]);
                }
            } else if (solid != null && STEP[d][1] == 0) {
                solid[d] = FULL_PLANE;
            }
        }
    }

    /**
     * Per direction d, a hash of what the neighbor in that direction reads of
     * this section ({@link #readNeighbors}: the touching layer's light, its
     * solid voxels and its empty columns), so a rewrite meshes a neighbor
     * again only when that changed. Mesher threads.
     */
    private long[] borderHashes(LodSection section, MaterialClassifier classes, int level) {
        long[] hashes = new long[6];
        int[] plane = new int[LodSection.SIZE];
        byte[] light = new byte[LodSection.SIZE * LodSection.SIZE];
        for (int d = 0; d < 6; d++) {
            // The neighbor in direction d reads this section across the opposite direction.
            int seen = d ^ 1;
            BinaryGreedyMesher.borderLight(section, seen, light, classes, 1 << level);
            BinaryGreedyMesher.borderPlane(section, seen, classes, true, plane);
            BinaryGreedyMesher.addEmptyColumns(section, seen, classes, plane);
            long h = 0x9E3779B97F4A7C15L;
            for (int row : plane) {
                h = (h ^ row) * 0x100000001B3L;
            }
            for (byte b : light) {
                h = (h ^ b) * 0x100000001B3L;
            }
            hashes[d] = h;
        }
        return hashes;
    }

    /**
     * Marks the resident neighbors whose view of {@code key} changed between
     * two meshes of it; {@code before} null when the earlier mesh had no data.
     */
    private void restaleNeighbors(long key, long[] before, long[] after) {
        for (int d = 0; d < 6; d++) {
            if (before != null && before[d] == after[d]) {
                continue;
            }
            int y = LodKey.y(key) + STEP[d][1];
            if (y < LodKey.Y_MIN || y > LodKey.Y_MAX) {
                continue;
            }
            long neighbor = LodKey.pack(LodKey.level(key), LodKey.x(key) + STEP[d][0], y, LodKey.z(key) + STEP[d][2]);
            Resident r = resident.get(neighbor);
            // The neighbor sees this section in the opposite direction.
            if (r != null && (r.groups[PLANT_GROUP] > r.opaqueCount || (r.borderFaces >> (d ^ 1) & 1) != 0)) {
                stale.add(neighbor);
                selectionDirty = true;
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
        int staleBefore = stale.size();
        if (!waitingStore.isEmpty()) {
            // Polled, not taken from the write's notice: that comes before the writer counts it done.
            LongIterator waiting = waitingStore.iterator();
            while (waiting.hasNext()) {
                if (stream == null || !stream.storing(waiting.nextLong())) {
                    waiting.remove();
                    selectionDirty = true;
                }
            }
        }
        Long key;
        while ((key = rewritten.poll()) != null) {
            existenceOps.add(key); // the selection's cache learns it (ADR-147)
            selectionDirty = true;
            if (resident.containsKey(key.longValue())) {
                stale.add(key.longValue());
                previewLayer.realWritten(key);
                // Its neighbors are meshed again once its own new mesh shows its borders changed
                // (restaleNeighbors): most rewrites at the generation front leave them as they were.
                continue;
            }
            if (requested.contains(key.longValue())) {
                // Its first mesh is being built and may have read the mirror before this write: a
                // pushed section's version is known before the mirror's writer stores it, so the
                // renderer meshed it empty and kept that mesh as current (holes, treeless land in
                // new terrain, ADR-085).
                rewrittenWhileMeshing.add(key.longValue());
            }
            previewLayer.realWritten(key);
            // Translucent faces of the neighbors were culled against the old data, and faces on
            // the shared border took their light from it, or full light while it was missing
            // (white sea floors along section borders until the neighbor itself changed).
            long k = key;
            for (int d = 0; d < 6; d++) {
                int y = LodKey.y(k) + STEP[d][1];
                if (y < LodKey.Y_MIN || y > LodKey.Y_MAX) {
                    continue;
                }
                long neighbor = LodKey.pack(LodKey.level(k), LodKey.x(k) + STEP[d][0], y, LodKey.z(k) + STEP[d][2]);
                Resident r = resident.get(neighbor);
                // The neighbor sees this section in the opposite direction.
                if (r != null && (r.groups[PLANT_GROUP] > r.opaqueCount || (r.borderFaces >> (d ^ 1) & 1) != 0)) {
                    stale.add(neighbor);
                }
            }
        }
        previewLayer.applyHeld(this::realReady, System.nanoTime());
        if (previewLayer.drainChanges(changedTiles)) {
            selectionDirty = true;
        }
        if (!changedTiles.isEmpty() || previewLayer.coarseChanged()) {
            long swap = swapSeq++;
            long now = System.nanoTime();
            previewLayer.prepareTouched(changedTiles);
            LongIterator keys = resident.keySet().iterator();
            while (keys.hasNext()) {
                long k = keys.nextLong();
                if (PreviewLayer.isPreview(k) && previewLayer.touched(k, changedTiles)) {
                    stale.add(k);
                    if (drawn.contains(k)) {
                        joinSwap(swap, k, now); // their new meshes go in together (ADR-092)
                    }
                }
            }
            changedTiles.clear();
        }
        if (stale.size() != staleBefore) {
            selectionDirty = true; // the selection orders stale sections with the wanted ones
        }
    }

    /**
     * Preview sections meshed again for one mask change, drawn when it
     * came: their new meshes go in together, in one frame. Put in one by
     * one as their jobs ended, a preview column showed for a frame or two
     * its new sea top without its new floor (lighter bands on the sea) or
     * the other way round (a dark strip) (ADR-092). Render thread.
     */
    /**
     * A swap's mesh whose quads are already in the quad buffer at
     * {@code start} (-1: not sent yet), outside any drawn range (ADR-148):
     * the swap's frame then only points the sections at them. 400 to 600
     * meshes sent in that frame took 10 to 17 ms.
     */
    private record Staged(MeshResult mesh, long start) {
    }

    private static final class Swap {
        final LongOpenHashSet waiting = new LongOpenHashSet();
        final java.util.List<Staged> ready = new java.util.ArrayList<>();
        final long since;

        Swap(long since) {
            this.since = since;
        }
    }

    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Swap> swaps =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap swapOf = newSwapOf();
    private long swapSeq;
    /** A swap goes in after this long anyway: a section of it left the view or was not meshed again. */
    private static final long SWAP_TIMEOUT_NANOS = 250_000_000L;
    /** Swaps put in since the last report, those at the time limit, and the longest wait. */
    private long swapsDone;
    private long swapsTimedOut;
    private long swapMaxNanos;

    private static it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap newSwapOf() {
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap map = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        map.defaultReturnValue(-1);
        return map;
    }

    /** Makes a drawn preview section, just marked stale, part of {@code swap}, leaving any older one. */
    private void joinSwap(long swap, long key, long now) {
        long old = swapOf.put(key, swap);
        if (old >= 0 && old != swap) {
            Swap o = swaps.get(old);
            if (o != null) {
                o.waiting.remove(key);
                o.ready.removeIf(st -> st.mesh().key == key && unstage(st)); // a newer mesh replaces it
            }
        }
        swaps.computeIfAbsent(swap, b -> new Swap(now)).waiting.add(key);
    }

    /**
     * Holds back a mesh that belongs to a swap until the whole swap is
     * meshed; a mesh begun before its section went stale goes in as usual.
     */
    private boolean deferSwap(MeshResult m) {
        long id = swapOf.get(m.key);
        if (id < 0 || stale.contains(m.key)) {
            return false;
        }
        swapOf.remove(m.key);
        Swap swap = swaps.get(id);
        if (swap == null) {
            return false;
        }
        swap.waiting.remove(m.key);
        if (m.generation == generation.get()) {
            long start = -1;
            if (m.quads.length > 0) {
                start = arena.allocate(m.quads.length); // no growth nor eviction here: sent at the swap if full
                if (start >= 0) {
                    long sent = System.nanoTime();
                    quads.upload(start * 8, m.quads, m.quads.length);
                    uploadQuadsNanos += System.nanoTime() - sent;
                }
            }
            swap.ready.add(new Staged(m, start));
        }
        return true;
    }

    /** Frees a staged mesh's quads; true, for removeIf. */
    private boolean unstage(Staged st) {
        if (st.start() >= 0) {
            arena.release(st.start(), st.mesh().quads.length);
        }
        return true;
    }

    /** Puts in the swaps whose sections are all meshed, or that waited too long. */
    private void flushSwaps(long now) {
        if (swaps.isEmpty()) {
            return;
        }
        var it = swaps.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Swap swap = e.getValue();
            boolean timedOut = now - swap.since > SWAP_TIMEOUT_NANOS;
            if (!swap.waiting.isEmpty() && !timedOut) {
                continue;
            }
            if (FrameProbe.enabled()) {
                FrameProbe.note("swap" + swap.ready.size() + (swap.waiting.isEmpty() ? "" : "timedOut"));
            }
            for (Staged st : swap.ready) {
                upload(st.mesh(), st.start());
            }
            LongIterator keys = swap.waiting.iterator();
            while (keys.hasNext()) {
                swapOf.remove(keys.nextLong());
            }
            swapsDone++;
            swapsTimedOut += swap.waiting.isEmpty() ? 0 : 1;
            swapMaxNanos = Math.max(swapMaxNanos, now - swap.since);
            it.remove();
        }
    }

    private void uploadMeshes() {
        long budget = UPLOAD_BUDGET_QUADS;
        long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
        MeshResult m;
        while (budget > 0 && (m = done.poll()) != null) {
            requested.remove(m.key);
            if (deferSwap(m)) {
                if (System.nanoTime() > deadline) {
                    break; // a swap's quads count in the frame's time too
                }
                continue;
            }
            budget -= upload(m, -1);
            if (System.nanoTime() > deadline) {
                break; // the rest on the next frames
            }
        }
        flushSwaps(System.nanoTime());
    }

    /**
     * Uploads one mesh; returns the quads it uploaded. {@code staged} is
     * where its quads already are (a swap's, ADR-148), -1 when not sent yet.
     * Render thread.
     */
    private long upload(MeshResult m, long staged) {
        // Written while its first mesh was being built: that mesh may hold the old data.
        boolean rewrittenMeanwhile = rewrittenWhileMeshing.remove(m.key);
        if (m.generation != generation.get()) {
            if (staged >= 0) {
                arena.release(staged, m.quads.length);
            }
            return 0;
        }
        if (stale.contains(m.key)) {
            selectionDirty = true; // changed while in flight: ordered again with the others
        }
        fillMeshes++;
        // Room for the quads first: a section kept empty for want of room was taken
        // as ready and never meshed again (holes for minutes after a spyglass).
        long start = staged;
        if (m.quads.length > 0 && staged < 0) {
            start = allocateQuads(m.quads.length);
            if (start < 0) {
                if (resident.containsKey(m.key)) {
                    stale.add(m.key); // keeps its old mesh, meshed again later
                }
                return 0;
            }
        }
        Resident r = resident.get(m.key);
        boolean fresh = r == null;
        if (FrameProbe.enabled()) {
            FrameProbe.note(probeName(m.key) + (fresh ? "new" : "") + (drawn.contains(m.key) ? "d" : "") + "q" + m.quads.length);
        }
        if (rewrittenMeanwhile) {
            stale.add(m.key);
            selectionDirty = true;
        }
        if (r == null) {
            r = new Resident(allocateSlot());
            slotKeys[r.slot] = m.key;
            slotLastUsed[r.slot] = System.nanoTime();
            resident.put(m.key, r);
            if (PreviewLayer.isPreview(m.key)) {
                previewResident.add(m.key);
            } else {
                realResident.add(m.key);
            }
            selectionDirty = true; // a parent may now descend
        } else if (r.quadCount > 0) {
            arena.release(r.start, r.quadCount);
        }
        r.quadCount = m.quads.length;
        r.opaqueCount = m.groups[Quad.DIRECTIONS];
        r.groups = m.groups;
        r.cutout = m.cutout;
        r.borderFaces = m.borderFaces;
        r.octants = m.octants;
        if (!PreviewLayer.isPreview(m.key)) {
            realOctants.put(m.key, m.octants);
        }
        if (!PreviewLayer.isPreview(m.key)) {
            noteHandOver(m.key, r.columns, m.columns);
        }
        r.columns = m.columns;
        long[] oldBorders = r.borders;
        r.borders = m.borders;
        if (!fresh && m.borders != null) {
            // Its new data changed what a neighbor reads there (or it had none before): that
            // neighbor is meshed again.
            restaleNeighbors(m.key, oldBorders, m.borders);
        }
        r.maxMaterial = m.maxMaterial;
        r.materials = m.materials;
        r.looksVersion = -1;
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
            r.biomeColumns = m.biomeColumns;
        }
        meshesUploaded++;
        if (r.quadCount > 0) {
            r.start = start;
            if (staged < 0) {
                long sent = System.nanoTime();
                quads.upload(start * 8, m.quads, m.quads.length);
                uploadQuadsNanos += System.nanoTime() - sent;
            }
        }
        writeSection(m.key, r);
        linkNeighbors(m.key, r);
        long blend = System.nanoTime();
        if (fresh) {
            blendSection(m.key, r);
        } else {
            // A new mesh of a drawn section: its slot holds its last blend meanwhile (ADR-148).
            blendDirty.add(m.key);
        }
        markBlendNeighbors(m.key);
        uploadBlendNanos += System.nanoTime() - blend;
        return r.quadCount;
    }

    /**
     * Room for {@code count} quads: the arena, grown up to {@link #MAX_QUADS},
     * then freed of the sections the last selection did not use; -1 when even
     * that is not enough (the result waits and is meshed again later).
     */
    private long allocateQuads(int count) {
        long start = arena.allocate(count);
        if (start < 0 && growArena(count)) {
            start = arena.allocate(count);
        }
        if (start < 0) {
            evictNotSelected();
            start = arena.allocate(count);
        }
        return start;
    }

    /** Frees every resident section the last selection did not use. */
    private void evictNotSelected() {
        evicted.clear();
        for (int slot = 0; slot < slotCount; slot++) {
            if (slotLastUsed[slot] < appliedSelectionTime && !relied(slotKeys[slot])) {
                evict(slotKeys[slot]);
            }
        }
        for (int i = 0, n = evicted.size(); i < n; i++) {
            unlinkNeighbors(evicted.getLong(i));
        }
        if (!evicted.isEmpty()) {
            selectionDirty = true;
        }
    }

    /** Frees a resident section, its key added to {@link #evicted} (the neighbors unlinked after). */
    private void evict(long key) {
        Resident r = resident.remove(key);
        if (r == null) {
            return;
        }
        evicted.add(key);
        previewResident.remove(key);
        realResident.remove(key);
        realOctants.remove(key);
        if (r.quadCount > 0) {
            arena.release(r.start, r.quadCount);
        }
        releaseSlot(r.slot);
        meshesEvicted++;
    }

    private boolean growArena(long needed) {
        long capacity = arena.capacity();
        long target = Math.max(capacity * 2, capacity + needed);
        if (target > MAX_QUADS) {
            return false;
        }
        long start = System.nanoTime();
        quads.grow(target * 8, capacity * 8);
        arena.grow(target);
        Farfield.LOG.info("LOD quad buffer grown to {} MB in {} ms", target * 8 >> 20,
                (System.nanoTime() - start) / 1_000_000);
        rememberQuads(target);
        return true;
    }

    /**
     * The quad buffer's start: the size the last session grew it to (ADR-144),
     * so a view that needs 256 MB does not stall on two growths, each a copy of
     * the whole buffer (89 and 178 ms frames while the first view loaded).
     */
    private static long rememberedQuads() {
        try {
            java.nio.file.Path file = Services.PLATFORM.getConfigDir().resolve(QUADS_FILE);
            if (java.nio.file.Files.exists(file)) {
                String[] parts = java.nio.file.Files.readString(file).trim().split("\\s+");
                long mb = Long.parseLong(parts[0]);
                if (parts.length > 1) {
                    rememberedSlots = Math.max(0, Math.min(1 << 20, Integer.parseInt(parts[1])));
                }
                long quads = Math.max(INITIAL_QUADS, Math.min(MAX_QUADS, (mb << 20) / 8));
                Farfield.LOG.info("LOD quad buffer starts at {} MB and {} section slots, what the last session needed",
                        quads * 8 >> 20, Math.max(1024, rememberedSlots));
                return quads;
            }
        } catch (java.io.IOException | RuntimeException e) {
            // A missing or unreadable file starts small, as before.
        }
        return INITIAL_QUADS;
    }

    private static void rememberQuads(long quads) {
        try {
            java.nio.file.Files.writeString(Services.PLATFORM.getConfigDir().resolve(QUADS_FILE),
                    (quads * 8 >> 20) + " " + rememberedSlots);
        } catch (java.io.IOException | RuntimeException e) {
            // The next session grows again.
        }
    }

    /**
     * Compacts the quad buffer into a smaller one once it has stayed mostly
     * empty for {@link #SHRINK_AFTER_NANOS}: a zoom grows it to hold a view's
     * worth of finer sections, which are freed after it. The residents' quads
     * are copied on the GPU one after another and their records point at
     * their new start; draw commands address quads from that start, so they
     * stay as they are.
     */
    private void shrinkArena(long now) {
        long capacity = arena.capacity();
        long used = arena.used();
        // The remembered start holds while the first view loads (still mostly empty), then the buffer may go
        // down to fit a smaller view, and that size is remembered instead.
        long floor = now - startedNanos < 60_000_000_000L ? startQuads : INITIAL_QUADS;
        long target = RangeAllocator.shrunkCapacity(capacity, used, floor);
        if (target < 0) {
            arenaLowSince = -1;
            return;
        }
        if (arenaLowSince < 0) {
            arenaLowSince = now;
            return;
        }
        if (now - arenaLowSince < SHRINK_AFTER_NANOS) {
            return;
        }
        arenaLowSince = -1;
        GlBuffer smaller = new GlBuffer(target * 8);
        RangeAllocator packed = new RangeAllocator(target);
        int moved = 0;
        ObjectIterator<Long2ObjectOpenHashMap.Entry<Resident>> it = resident.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectOpenHashMap.Entry<Resident> e = it.next();
            Resident r = e.getValue();
            if (r.quadCount == 0) {
                continue;
            }
            long start = packed.allocate(r.quadCount);
            quads.copyTo(smaller, r.start * 8, start * 8, (long) r.quadCount * 8);
            r.start = start;
            writeSection(e.getLongKey(), r);
            moved++;
        }
        quads.close();
        quads = smaller;
        arena = packed;
        Farfield.LOG.info("LOD quad buffer shrunk from {} to {} MB ({} MB used, {} sections moved)",
                capacity * 8 >> 20, target * 8 >> 20, used * 8 >> 20, moved);
        rememberQuads(arena.capacity());
    }

    private int allocateSlot() {
        if (freeSlotCount > 0) {
            return freeSlots[--freeSlotCount];
        }
        int slot = slotCount++;
        if (slotCount > slotKeys.length) {
            slotKeys = java.util.Arrays.copyOf(slotKeys, slotKeys.length * 2);
            slotLastUsed = java.util.Arrays.copyOf(slotLastUsed, slotLastUsed.length * 2);
        }
        if (slotCount > rememberedSlots) {
            // The per-slot buffers double past a power of two: the next session starts at the one reached.
            rememberedSlots = Integer.highestOneBit(slotCount - 1) << 1;
            rememberQuads(arena.capacity());
        }
        long needed = (long) slotCount * SECTION_BYTES;
        if (needed > sectionInfo.size()) {
            sectionInfo.grow(sectionInfo.size() * 2, sectionInfo.size());
        }
        if ((long) slotCount * CULL_INTS * 4 > cullGroups.size()) {
            cullGroups.grow(cullGroups.size() * 2, cullGroups.size());
        }
        return slot;
    }

    private void releaseSlot(int slot) {
        slotLastUsed[slot] = Long.MAX_VALUE;
        // Its GPU record emptied, and the slot kept until the next selection (ADR-146): the culled
        // candidates of the last selection may still name it.
        cullGroups.upload((long) slot * CULL_INTS * 4, NO_CULL_GROUPS);
        pendingFreeSlots.add(slot);
    }

    /** The slots freed since the last selection go back to the free list: no candidate names them now. */
    private void freePendingSlots() {
        for (int i = 0, n = pendingFreeSlots.size(); i < n; i++) {
            freeSlot(pendingFreeSlots.getInt(i));
        }
        pendingFreeSlots.clear();
    }

    private void freeSlot(int slot) {
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
                (int) r.start, r.quadCount, r.occluders ? 1 : 0, PreviewLayer.isPreview(key) ? 1 : 0});
        int[] record = new int[CULL_INTS];
        System.arraycopy(r.groups, 0, record, 0, Math.min(PLANT_GROUP + 2, r.groups.length));
        record[PLANT_GROUP + 2] = r.cutout ? 1 : 0;
        cullGroups.upload((long) r.slot * CULL_INTS * 4, record);
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
            writeNeighbor((long) n.slot * NEIGHBOR_INTS + (26 - i), r.occluders ? r.slot + 1 : 0);
        }
        neighborSlots.upload((long) r.slot * NEIGHBOR_INTS * 4, neighborScratch);
        if (!neighborWrites.isEmpty()) {
            // The row just written holds the current neighbors: an earlier write into it is outdated.
            int row = r.slot * NEIGHBOR_INTS;
            for (int i = 0; i < NEIGHBOR_INTS; i++) {
                neighborWrites.remove(row + i);
            }
        }
    }

    /** One int of the neighbor tables, written by the next {@link #flushNeighborWrites}. */
    private void writeNeighbor(long index, int value) {
        if (scatterProgram == null) {
            oneInt[0] = value;
            neighborSlots.upload(index * 4, oneInt);
            return;
        }
        neighborWrites.put((int) index, value);
    }

    /** Writes this frame's single ints into the neighbor tables in one compute pass (ADR-148). */
    private void flushNeighborWrites() {
        int n = neighborWrites.size();
        if (n == 0) {
            return;
        }
        if (scatterScratch.capacity() < n * 2) {
            scatterScratch = org.lwjgl.BufferUtils.createIntBuffer(Math.max(n * 2, scatterScratch.capacity() * 2));
        }
        scatterScratch.clear();
        var it = neighborWrites.int2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            scatterScratch.put(e.getIntKey()).put(e.getIntValue());
        }
        scatterScratch.flip();
        neighborWrites.clear();
        long bytes = (long) n * 8;
        if (bytes > scatterPairs.size()) {
            scatterPairs.grow(Math.max(bytes, scatterPairs.size() * 2), 0);
        }
        scatterPairs.upload(0, scatterScratch);
        GlStateManager._glUseProgram(scatterProgram.id());
        glUniform1i(scatterCountU, n);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, neighborSlots.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, scatterPairs.id());
        glDispatchCompute((n + 63) / 64, 1, 1);
        // Read by the draws as storage, and copied when the buffer grows.
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        GlStateManager._glUseProgram(0);
    }

    /**
     * Blends a section's columns into its slot (BiomeBlend) when its level and
     * the radius send its fragments that way; otherwise clears the slot's
     * radius, so the shader never reads the blends of the slot's last section.
     */
    private void blendSection(long key, Resident r) {
        long needed = (long) (r.slot + 1) * BLEND_SLOT_INTS * 4;
        if (needed > blendedTints.size()) {
            blendedTints.grow(Math.max(needed, blendedTints.size() * 2), blendedTints.size());
        }
        int level = drawLevel(key);
        if (r.biomeColumns == null || !BiomeBlend.needed(blendRadius, level)) {
            oneInt[0] = 0;
            blendedTints.upload(((long) r.slot * BLEND_SLOT_INTS + BLEND_SLOT_INTS - 1) * 4, oneInt);
            return;
        }
        int keyLevel = LodKey.level(key);
        int x = LodKey.x(key);
        int y = LodKey.y(key);
        int z = LodKey.z(key);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int i = (dx + 1) * 3 + dz + 1;
                if (i == 4) {
                    blendSections[i] = r.biomeColumns;
                    continue;
                }
                // The neighbors the shader sees: those of the neighbor table, which holds them with occluders.
                Resident n = resident.get(LodKey.pack(keyLevel, x + dx, y, z + dz));
                blendSections[i] = n != null && n.occluders ? n.biomeColumns : null;
            }
        }
        BiomeBlend.blend(blendSections, tints.table(), tints.length() / BiomeTints.KINDS, tints.kinds(),
                BiomeTints.KINDS, level, blendRadius, blendScratch);
        blendScratch[BLEND_SLOT_INTS - 1] = blendRadius + 1;
        blendedTints.upload((long) r.slot * BLEND_SLOT_INTS * 4, blendScratch);
    }

    /** Its horizontal neighbors blend with a section's columns: they are blended again. */
    private void markBlendNeighbors(long key) {
        if (blendRadius <= 0) {
            return;
        }
        int keyLevel = LodKey.level(key);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long k = LodKey.pack(keyLevel, LodKey.x(key) + dx, LodKey.y(key), LodKey.z(key) + dz);
                if ((dx != 0 || dz != 0) && resident.containsKey(k)) {
                    blendDirty.add(k);
                }
            }
        }
    }

    /** Follows vanilla's Biome Blend, then blends the waiting sections within {@link #BLEND_BUDGET_NANOS}. */
    private void updateBlends() {
        int radius = Minecraft.getInstance().options.biomeBlendRadius().get();
        if (radius != blendRadius) {
            blendRadius = radius;
            blendDirty.clear();
            blendDirty.addAll(resident.keySet());
        }
        if (blendDirty.isEmpty()) {
            return;
        }
        long end = System.nanoTime() + BLEND_BUDGET_NANOS;
        LongIterator it = blendDirty.iterator();
        while (it.hasNext() && System.nanoTime() < end) {
            long key = it.nextLong();
            it.remove();
            Resident r = resident.get(key);
            if (r != null) {
                blendSection(key, r);
            }
        }
    }

    /** Removes an evicted section from its resident neighbors' tables. */
    private void unlinkNeighbors(long key) {
        int level = LodKey.level(key);
        for (int i = 0; i < NEIGHBOR_INTS; i++) {
            int ny = LodKey.y(key) + i / 3 % 3 - 1;
            if (i == 13 || ny < LodKey.Y_MIN || ny > LodKey.Y_MAX) {
                continue;
            }
            Resident n = resident.get(LodKey.pack(level, LodKey.x(key) + i / 9 - 1, ny, LodKey.z(key) + i % 3 - 1));
            if (n != null) {
                writeNeighbor((long) n.slot * NEIGHBOR_INTS + (26 - i), 0);
            }
        }
    }

    /**
     * Frees resident sections unused for {@link #EVICT_AFTER_NANOS}, at most
     * {@link #EVICT_PER_FRAME} a call (ADR-143): after a teleport tens of
     * thousands went at once, each freed in the quad buffer and unlinked from
     * its neighbors, a frame of 25 to 50 ms. Returns whether some were left.
     */
    private boolean evictUnused(long now) {
        evicted.clear();
        boolean more = false;
        for (int slot = 0; slot < slotCount; slot++) {
            // A free slot's MAX_VALUE gives a negative difference.
            if (now - slotLastUsed[slot] <= EVICT_AFTER_NANOS || relied(slotKeys[slot])) {
                continue;
            }
            if (evicted.size() >= EVICT_PER_FRAME) {
                more = true;
                break;
            }
            evict(slotKeys[slot]);
            selectionDirty = true;
        }
        for (int i = 0, n = evicted.size(); i < n; i++) {
            unlinkNeighbors(evicted.getLong(i));
        }
        return more;
    }

    /**
     * Time this frame may spend building looks: {@link #LOOKS_BUDGET_NANOS},
     * or, while more than {@link #LOOKS_BACKLOG} wait, what the previous
     * frame left before 60 fps (never less): the rest of the frame keeps its
     * time, and a game already under 60 fps loses no more than before.
     */
    private long looksBudget(long frameStart) {
        long interval = lastFrameStart == 0 ? 0 : frameStart - lastFrameStart;
        lastFrameStart = frameStart;
        int waiting = world.blockStates().size() - colors.count();
        if (waiting <= LOOKS_BACKLOG || interval <= 0) {
            return LOOKS_BUDGET_NANOS;
        }
        long rest = interval - lastLooksNanos;
        return Math.max(LOOKS_BUDGET_NANOS, Math.min(LOOKS_FRAME_NANOS, LOOKS_FRAME_NANOS - rest));
    }

    /**
     * Builds and uploads the colors of new block states and the biome tints.
     * A resource reload or another level builds everything again, and meshes
     * again what is resident (which materials depend on the biome may change).
     */
    private void uploadColors(Minecraft mc, ClientLevel level, long budget) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        if (missing != missingModel || level != colorLevel) {
            if (missingModel != null) {
                stale.addAll(resident.keySet());
                selectionDirty = true;
            }
            missingModel = missing;
            colorLevel = level;
            colors.reset();
            tints.reset();
            if (colors.cache() != null) {
                appendLooks(colors.cache()); // what the last world baked, before its cache goes
            }
            colors.setCache(openLooksCache(mc));
        }
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Biome plains = biomes.get(Biomes.PLAINS);
        if (plains == null) {
            plains = biomes.stream().findFirst().orElse(null);
            if (plains == null) {
                return;
            }
        }
        int waiting = world.blockStates().size() - colors.count();
        if (waiting > LOOKS_BACKLOG && looksBacklogStart == 0) {
            looksBacklogStart = System.nanoTime();
            looksBacklog = waiting;
        }
        long looksStart = System.nanoTime();
        int first = colors.update(world.blockStates(), world.materials(), tints, plains, budget);
        lastLooksNanos = System.nanoTime() - looksStart;
        looksFromCache += colors.takeFromCache();
        boolean allBuilt = colors.count() == world.blockStates().size();
        if (looksBacklogStart != 0 && allBuilt) {
            if (RenderSettings.performanceLogs()) {
                Farfield.LOG.info("LOD looks: {} built in {} ms, {} of them from the cache", looksBacklog,
                        (System.nanoTime() - looksBacklogStart) / 1_000_000, looksFromCache);
            }
            looksBacklogStart = 0;
            looksFromCache = 0;
        }
        // New looks go to the cache file once every look is built, at most every LOOKS_APPEND_NANOS:
        // exploring brings a few new block states at a time (ADR-081).
        long now = System.nanoTime();
        if (allBuilt && colors.cache() != null && colors.cache().hasPending()
                && now - lastLooksAppend > LOOKS_APPEND_NANOS) {
            lastLooksAppend = now;
            appendLooks(colors.cache());
        }
        if (first >= 0 && !provisional.isEmpty()) {
            // Meshed with guessed flags for materials without their look: meshed again once they have one.
            LongIterator it = provisional.iterator();
            while (it.hasNext()) {
                long k = it.nextLong();
                Resident r = resident.get(k);
                if (r == null || looksReady(r)) {
                    if (r != null) {
                        stale.add(k);
                    }
                    it.remove();
                    selectionDirty = true;
                }
            }
        }
        if (first >= 0) {
            int stride = MaterialColors.STRIDE;
            int[] ids = colors.drainBuilt();
            long needed = (long) world.blockStates().size() * stride * 4;
            if (needed > colorBuffer.size()) {
                colorBuffer.grow(Math.max(needed, colorBuffer.size() * 2), colorBuffer.size());
            }
            int[] all = colors.colors();
            for (int i = 0; i < ids.length; ) {
                int from = ids[i];
                int to = from + 1;
                while (++i < ids.length && ids[i] == to) {
                    to++;
                }
                colorBuffer.upload((long) from * stride * 4, java.util.Arrays.copyOfRange(all, from * stride, to * stride));
            }
        }
        atlas = faceTextures.upload();
        if (tints.update(world.biomes(), biomes, plains)) {
            long needed = (long) tints.length() * 4;
            if (needed > biomeBuffer.size()) {
                biomeBuffer.grow(Math.max(needed, biomeBuffer.size() * 2), 0);
            }
            biomeBuffer.upload(0, java.util.Arrays.copyOf(tints.table(), tints.length()));
            blendDirty.addAll(resident.keySet()); // their blends hold the old tints
        }
    }

    /** Appends a cache's new looks to its file, on a thread of its own. */
    private static void appendLooks(LooksCache cache) {
        cache.appendPending(outcome -> {
            if (outcome instanceof Exception e) {
                Farfield.LOG.warn("LOD looks cache not written", e);
            } else if (RenderSettings.performanceLogs()) {
                Farfield.LOG.info("LOD looks cache: {} looks appended, {} in all", outcome, cache.size());
            }
        });
    }

    /**
     * The looks cache for the loaded mods and the resource packs in use
     * ({@link LooksCache}), read on the render thread once per world or
     * resource reload.
     */
    private static LooksCache openLooksCache(Minecraft mc) {
        long start = System.nanoTime();
        long fingerprint = LooksCache.fingerprint(MaterialColors.BAKE_VERSION, Services.PLATFORM.modVersions(),
                java.util.List.copyOf(mc.getResourcePackRepository().getSelectedIds()));
        java.nio.file.Path file = Services.PLATFORM.getGameDir().resolve("farfield").resolve("looks.bin");
        LooksCache cache = LooksCache.open(file, fingerprint);
        Farfield.LOG.info("LOD looks cache {}: {} looks and {} tiles ({}{}) in {} ms", file, cache.size(),
                cache.tilesRead(), switch (cache.status()) {
                    case READ -> "read";
                    case MISSING -> "none yet";
                    case OTHER_FINGERPRINT -> "for other mods or resource packs, baked again";
                    case UNREADABLE -> "unreadable or of an older format, baked again";
                }, cache.recordsDropped() > 0 ? ", a tail cut short dropped" : "",
                (System.nanoTime() - start) / 1_000_000);
        return cache;
    }

    private void updateFrustum(Matrix4f viewRotation, Matrix4f projection) {
        float m11 = Math.abs(projection.m11());
        float m00 = Math.abs(projection.m00());
        float fovY = (float) (2 * Math.atan(1 / m11)) * 1.1f;
        // Far plane past the farthest selected section; near plane no closer than far / 4e6, or the
        // far plane's row is lost in float (at the border's 67 M blocks every section was culled, E.3d).
        float far = (float) Math.min(MAX_DISTANCE * 4, Math.max(Math.max(1024, selectionDistance() * 2 + (32 << 15) * 2.0),
                drawnExtent * 1.25)); // the ground seen from far above too (ADR-141)
        cullMatrix.setPerspective(fovY, m11 / m00, Math.max(1f, far / 4_000_000f), far).mul(viewRotation);
        frustum.set(cullMatrix);
    }

    /** Squared distance from the camera to a section's center, in blocks. */
    private float centerDistanceSq(long key, Vec3 cam) {
        int size = LodSection.SIZE << drawLevel(key);
        float x = (float) (LodKey.x(key) * (double) size + size * 0.5 - cam.x);
        float y = (float) (LodKey.y(key) * (double) size + size * 0.5 - cam.y);
        float z = (float) (LodKey.z(key) * (double) size + size * 0.5 - cam.z);
        return x * x + y * y + z * z;
    }

    /** Distance from the camera to the nearest point of a section, in blocks. */
    private static double nearestDistance(long key, Vec3 cam) {
        int size = LodSection.SIZE << drawLevel(key);
        double x = LodKey.x(key) * (double) size;
        double y = LodKey.y(key) * (double) size;
        double z = LodKey.z(key) * (double) size;
        double dx = Math.max(0, Math.max(x - cam.x, cam.x - (x + size)));
        double dy = Math.max(0, Math.max(y - cam.y, cam.y - (y + size)));
        double dz = Math.max(0, Math.max(z - cam.z, cam.z - (z + size)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Whether a section overlaps the frustum of the last frame. */
    private boolean inView(long key, Vec3 cam) {
        int size = LodSection.SIZE << drawLevel(key);
        float x = (float) (LodKey.x(key) * (double) size - cam.x);
        float y = (float) (LodKey.y(key) * (double) size - cam.y);
        float z = (float) (LodKey.z(key) * (double) size - cam.z);
        return frustum.testAab(x, y, z, x + size, y + size, z + size);
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
        if (level > 1 || gridWidth <= 0 || selector.forcedLevel() >= 0) {
            return false; // under a level lock the LODs draw over the game's terrain
        }
        if (packLink != null && packLink.mode() == ShaderPackLink.DH) {
            return false; // under a pack's DH contract the LODs lie under the game's terrain too (ADR-119)
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
        for (int slot = 0; slot < TIMER_SLOTS; slot++) {
            if (!packTranslucentPending[slot]
                    || glGetQueryObjecti(packTranslucentQueries[slot * 2 + 1], GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) {
                continue;
            }
            gpuPassNanos[3] += glGetQueryObjectui64(packTranslucentQueries[slot * 2 + 1], GL_QUERY_RESULT)
                    - glGetQueryObjectui64(packTranslucentQueries[slot * 2], GL_QUERY_RESULT);
            packTranslucentPending[slot] = false;
        }
    }

    /** Binds a program variant and sets its uniforms for this frame ({@link #viewProj} set). */
    /** The ambient occlusion state last logged: -1 none yet, 0 off, 1 on. */
    private int loggedOcclusion = -1;

    private void use(Shader s, Vec3 cam, Minecraft mc, boolean translucent) {
        int blockX = (int) Math.floor(cam.x);
        int blockY = (int) Math.floor(cam.y);
        int blockZ = (int) Math.floor(cam.z);
        float[] fogColor = RenderSystem.getShaderFogColor();
        GlStateManager._glUseProgram(s.program.id());
        if (s.pack) {
            packLink.bind(s.program.id());
        }
        glUniformMatrix4fv(s.viewProj, false, viewProj.get(matrix));
        glUniform3i(s.cameraBlock, blockX, blockY, blockZ);
        glUniform3f(s.cameraFrac, (float) (cam.x - blockX), (float) (cam.y - blockY), (float) (cam.z - blockZ));
        glUniform2f(s.depthRange, depthNear, depthFar);
        glUniform1i(s.renderDistance, mc.options.getEffectiveRenderDistance());
        glUniform3i(s.gridMin, gridMinX, gridMinY, gridMinZ);
        // A level lock draws the LODs where the game draws too: an empty grid gives way nowhere.
        boolean locked = selector.forcedLevel() >= 0;
        glUniform2i(s.gridSize, locked ? 0 : gridWidth, locked ? 0 : gridHeight);
        glUniform1i(s.sodiumRule, sodiumRule ? 1 : 0);
        glUniform2i(s.hiddenMin, hiddenMinX, hiddenMinZ);
        glUniform1i(s.lightmap, LIGHTMAP_UNIT);
        glUniform4f(s.fogColor, fogColor[0], fogColor[1], fogColor[2], 1f);
        if (pushedFog || followVanillaFog) {
            // The game's terrain fog, set up just before the LODs draw (moved out by
            // the fog mixin, or kept); its shape only when kept, as the LOD fog was a sphere.
            glUniform2f(s.fog, RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            glUniform1i(s.fogCylinder, followVanillaFog && RenderSystem.getShaderFogShape() == FogShape.CYLINDER ? 1 : 0);
        } else {
            // The fog mixin did not run or left a mod's fog alone: the LODs' own far fog.
            // None when the player hid it (ADR-140): far past anything drawn.
            int shown = thickFogDimension ? RenderSettings.thickFogDistance() : RenderSettings.fogDistance();
            float end = shown == RenderConfig.FOG_OFF ? 1e30f
                    : (float) Math.max(RenderConfig.MIN_FOG_DISTANCE, selectionDistance());
            glUniform2f(s.fog, end * 0.6f, end);
            glUniform1i(s.fogCylinder, 0);
        }
        glUniform1i(s.translucent, translucent ? 1 : 0);
        glUniform1i(s.debugLevels, RenderSettings.debugLevels() ? 1 : 0);
        // Vanilla's Smooth Lighting option, as the game's terrain (ADR-116); the debug switch on top.
        boolean smoothLighting = mc.options.ambientOcclusion().get();
        int occlusion = smoothLighting && RenderSettings.ambientOcclusion() ? 1 : 0;
        if (occlusion != loggedOcclusion) {
            loggedOcclusion = occlusion;
            Farfield.LOG.info("LOD ambient occlusion {} (Smooth Lighting {}, debug switch {})",
                    occlusion == 1 ? "on" : "off", smoothLighting ? "on" : "off",
                    RenderSettings.ambientOcclusion() ? "on" : "off");
        }
        glUniform1i(s.ambientOcclusion, occlusion);
        // Vanilla's Biome Blend option (0 off, else the radius in blocks), as the game's terrain.
        glUniform1i(s.biomeBlend, mc.options.biomeBlendRadius().get());
        glUniform1i(s.atlas, ATLAS_UNIT);
        glUniform1i(s.gameDepth, GAME_DEPTH_UNIT);
        glUniform2f(s.atlasSize, faceTextures.width(), Math.max(1, faceTextures.height()));
        int textureDistance = RenderSettings.textureDistance();
        glUniform1f(s.textureEnd, atlas == 0 ? 0
                : textureDistance == RenderSettings.ALL_DISTANCES ? Float.MAX_VALUE : textureDistance);
    }

    /**
     * The shader pack path failed on something of the pack's or Iris's:
     * LODs off under this pack with the reason, never a crash of the game.
     */
    private void packFailed(RuntimeException e) {
        Farfield.LOG.error("Shader pack LODs: failed under this pack", e);
        try {
            packLink.fail(e.toString());
        } catch (RuntimeException ignored) {
            // the reason is logged above
        }
    }

    /**
     * The pack's block id of every material, again when materials were added
     * or the pack changed: a few thousand map lookups, rarely.
     */
    private void updatePackIds() {
        int count = world == null ? 0 : world.blockStates().size();
        if (packIds != null && count == packIdsCount && packLink.idsGeneration() == packIdsGeneration) {
            return;
        }
        if (packIdsScratch.length < Math.max(1, count)) {
            packIdsScratch = new int[Math.max(1024, Integer.highestOneBit(Math.max(1, count)) << 1)];
        }
        java.util.Arrays.fill(packIdsScratch, 0);
        int named = 0;
        for (int id = 0; id < count; id++) {
            String key = world.blockStates().key(id);
            int custom = key == null ? 0 : packLink.customId(io.lakio.farfield.lod.BlockKeys.state(key));
            packIdsScratch[id] = custom;
            if (custom != 0) {
                named++;
            }
        }
        long bytes = (long) packIdsScratch.length * 4;
        if (packIds == null) {
            packIds = new GlBuffer(bytes);
        } else if (packIds.size() < bytes) {
            packIds.grow(bytes, 0);
        }
        packIds.upload(0, packIdsScratch);
        if (packIdsGeneration != packLink.idsGeneration()) {
            Farfield.LOG.info("Shader pack LODs: {} of {} materials have a block id of the pack", named, count);
        }
        packIdsCount = count;
        packIdsGeneration = packLink.idsGeneration();
    }

    /**
     * Called from the level renderer mixin at the game's translucent stage:
     * under a shader pack, the translucent LODs this frame's draw left.
     */
    public static void renderTranslucent(boolean beforeDeferred) {
        LodRenderer r = INSTANCE;
        // DH's water draws after the pack's deferred passes, as Iris draws Distant Horizons' (ADR-119).
        if (r.translucentPending && !(beforeDeferred && r.translucentAfterDeferred)) {
            r.translucentPending = false;
            if (r.translucentStage != (beforeDeferred ? 1 : 2)) {
                r.translucentStage = beforeDeferred ? 1 : 2;
                Farfield.LOG.info("Shader pack LODs: translucent LODs drawn {}", beforeDeferred
                        ? "before the pack's deferred passes" : r.translucentAfterDeferred
                        ? "at the game's translucent stage, after the pack's deferred passes (DH contract)"
                        : "at the game's translucent stage (Iris's hook missing)");
            }
            try {
                r.drawPackTranslucent(Minecraft.getInstance());
            } catch (RuntimeException e) {
                r.packFailed(e);
            }
        }
    }

    /** Where the translucent LODs last drew under a pack: 0 not yet, 1 before deferred, 2 the fallback. */
    private int translucentStage;

    private void drawPackTranslucent(Minecraft mc) {
        if (!packLink.beginTranslucent()) {
            return;
        }
        int previousUnit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL_TEXTURE0 + ATLAS_UNIT);
        GlStateManager._bindTexture(atlas);
        GlStateManager._activeTexture(GL_TEXTURE0 + LIGHTMAP_UNIT);
        GlStateManager._bindTexture(lightmap(mc));
        GlStateManager._activeTexture(previousUnit);
        org.lwjgl.opengl.GL45C.glBindTextureUnit(GAME_DEPTH_UNIT, packLink.gameDepthTexture());
        boolean dh = translucentShader == dhTranslucent;

        GlStateManager._glBindVertexArray(vao);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, quads.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, sectionInfo.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, colorBuffer.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, biomeBuffer.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, occupancy.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, neighborSlots.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, drawnSections.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, biomeColumns.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, previewHidden.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 10, blendedTints.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, packIds.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect.id());
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        // The pack reads the nearest LOD surface, translucent ones included.
        RenderSystem.depthMask(true);
        if (dh) {
            RenderSystem.disableCull(); // as Iris draws DH's water
        } else {
            RenderSystem.enableCull();
        }
        RenderSystem.colorMask(true, true, true, true);
        int slot = packTranslucentSlot;
        boolean timed = !packTranslucentPending[slot];
        if (timed) {
            glQueryCounter(packTranslucentQueries[slot * 2], GL_TIMESTAMP);
        }
        use(translucentShader, translucentCam, mc, true);
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, translucentOffset, translucentCount, 0);
        if (dh) {
            RenderSystem.enableCull();
        }
        if (timed) {
            glQueryCounter(packTranslucentQueries[slot * 2 + 1], GL_TIMESTAMP);
            packTranslucentPending[slot] = true;
            packTranslucentSlot = (slot + 1) % TIMER_SLOTS;
        }

        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        GlStateManager._glUseProgram(0);
        org.lwjgl.opengl.GL45C.glBindTextureUnit(GAME_DEPTH_UNIT, 0);
        packLink.endTranslucent();
    }

    private void draw(Vec3 cam, Matrix4f viewRotation, Matrix4f projection, Minecraft mc) {
        int solidDraws = draws[SOLID];
        int discardDraws = draws[DISCARD];
        int translucentDraws = draws[TRANSLUCENT];
        int plantDraws = draws[PLANTS];
        long opaqueBytes = (long) (solidDraws + discardDraws) * COMMAND_BYTES;
        long plantOffset = opaqueBytes + (long) translucentDraws * COMMAND_BYTES;
        long bytes = plantOffset + (long) plantDraws * COMMAND_BYTES;
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

        // Under a shader pack, its own target and program code, or nothing (ADR-113).
        int packMode;
        try {
            packMode = packLink == null ? ShaderPackLink.VANILLA : packLink.begin();
        } catch (RuntimeException e) {
            packFailed(e);
            return;
        }
        if (packMode == ShaderPackLink.NONE) {
            return;
        }
        boolean packed = packMode == ShaderPackLink.VOXY;
        boolean dh = packMode == ShaderPackLink.DH;
        if (packed && !packShaders() || dh && !dhShaders()) {
            packLink.end();
            return;
        }
        if (packed || dh) {
            updatePackIds();
        }

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
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, previewHidden.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 10, blendedTints.id());
        if (packed || dh) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, packIds.id());
        }
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
        // Under DH's contract the pack's own vertex shader places the faces: a
        // pre-pass of ours would not match its depth to the bit (GL_EQUAL).
        boolean prepass = RenderSettings.depthPrepass() && !dh;
        Shader shaded = dh ? dhOpaque : packed ? (prepass ? packShaded : packPlain) : (prepass ? earlyZ : plain);
        int opaqueDraws = solidDraws + discardDraws;
        boolean culled = gpuCandidates > 0 && cullProgram != null;
        if (prepass && (opaqueDraws > 0 || culled)) {
            RenderSystem.colorMask(false, false, false, false);
            if (solidDraws > 0 || culled) {
                use(packed && packDepthSolid != null ? packDepthSolid : depthSolid, cam, mc, false);
                if (solidDraws > 0) {
                    glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0, solidDraws, 0);
                }
                drawCulled(0);
            }
            if (discardDraws > 0 || culled) {
                use(packed && packDepthOnly != null ? packDepthOnly : depthOnly, cam, mc, false);
                if (discardDraws > 0) {
                    glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, (long) solidDraws * COMMAND_BYTES,
                            discardDraws, 0);
                }
                drawCulled(1);
            }
            RenderSystem.colorMask(true, true, true, true);
        }
        if (timed) {
            glQueryCounter(timerQueries[slot * TIMESTAMPS + 1], GL_TIMESTAMP);
        }
        if (opaqueDraws > 0 || culled) {
            use(shaded, cam, mc, false);
            if (prepass) {
                RenderSystem.depthFunc(GL_EQUAL);
                RenderSystem.depthMask(false);
            }
            if (opaqueDraws > 0) {
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0, opaqueDraws, 0);
            }
            drawCulled(0);
            drawCulled(1);
            RenderSystem.depthFunc(GL_LEQUAL);
            RenderSystem.depthMask(true);
        }
        if (timed) {
            glQueryCounter(timerQueries[slot * TIMESTAMPS + 2], GL_TIMESTAMP);
        }
        // Under DH's contract plants are left out: without their texture to cut, each would be a solid rectangle.
        if ((plantDraws > 0 || culled && RenderSettings.drawPlants()) && !dh) {
            // Plants: once, depth-tested against the opaque faces and writing their own,
            // holes cut out; the pre-pass would draw their thin quads twice.
            use(packed ? packPlants : plants, cam, mc, false);
            if (plantDraws > 0) {
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, plantOffset, plantDraws, 0);
            }
            drawCulled(2);
        }
        if (timed) {
            glQueryCounter(timerQueries[slot * TIMESTAMPS + 3], GL_TIMESTAMP);
        }
        // Under a shader pack, translucent LODs wait for voxy_translucent (C.1b).
        if (translucentDraws > 0 && RenderSettings.drawTranslucent() && !packed && !dh) {
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
            glQueryCounter(timerQueries[slot * TIMESTAMPS + 4], GL_TIMESTAMP);
            timerPending[slot] = true;
            timerSlot = (slot + 1) % TIMER_SLOTS;
        }
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        GlStateManager._glUseProgram(0);
        if (packed || dh) {
            // The LODs drew into their own depth texture: the game's depth is untouched.
            packLink.end();
            // Translucent LODs at the game's translucent stage: Voxy's before the pack's deferred passes, DH's after.
            translucentShader = dh ? dhTranslucent : packTranslucent;
            translucentAfterDeferred = dh;
            translucentPending = translucentDraws > 0 && RenderSettings.drawTranslucent() && translucentShader != null;
            translucentCam = cam;
            translucentOffset = opaqueBytes;
            translucentCount = translucentDraws;
            return;
        }
        // Vanilla terrain draws next with its own depth range: start it clean.
        RenderSystem.clear(GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }
}
