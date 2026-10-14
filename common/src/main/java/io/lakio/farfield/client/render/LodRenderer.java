package io.lakio.farfield.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.BinaryGreedyMesher;
import io.lakio.farfield.core.mesh.MeshBuffer;
import io.lakio.farfield.core.mesh.Quad;
import io.lakio.farfield.core.select.LodSelector;
import io.lakio.farfield.core.util.RangeAllocator;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.client.LodWorldProvider;
import io.lakio.farfield.lod.RenderToggle;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Draws the LOD sections of the current dimension beyond the vanilla render
 * distance (plan item E.4), on the minimal OpenGL 4.6 RHI (E.3).
 *
 * <ul>
 *   <li>Data: the {@link LodWorld} that {@link LodWorldProvider} gives for
 *       the current dimension (ADR-022).</li>
 *   <li>Selection: {@link LodSelector} each frame; missing meshes are built
 *       by {@link BinaryGreedyMesher} on two low-priority threads and
 *       uploaded on the render thread under a per-frame budget; a section
 *       the server rewrites is meshed again.</li>
 *   <li>GPU: quads in one storage buffer (first-fit ranges, grown on
 *       demand), one 32-byte record per resident section, colors per block
 *       state; one {@code glMultiDrawArraysIndirect} per frame, vertex
 *       pulling in {@code lod.vert}.</li>
 *   <li>Hand-off: drawn after the sky and before the terrain, with its own
 *       depth range; the depth buffer is cleared afterwards so vanilla
 *       terrain covers it, and fragments within the vanilla distance are
 *       discarded.</li>
 * </ul>
 */
public final class LodRenderer {

    private static final LodRenderer INSTANCE = new LodRenderer();

    private static final double DETAIL = 4.0;
    private static final double MAX_DISTANCE = 4096;
    private static final int MAX_IN_FLIGHT = 64;
    private static final long UPLOAD_BUDGET_QUADS = 1 << 20;
    private static final long INITIAL_QUADS = 8L << 20;
    private static final long MAX_QUADS = 128L << 20;
    private static final int SECTION_BYTES = 32;
    /** A resident mesh the selection has not used for this long is freed. */
    private static final long EVICT_AFTER_NANOS = 10_000_000_000L;

    private boolean initialized;
    private boolean disabled;
    private GlProgram program;
    private GlBuffer quads;
    private GlBuffer sectionInfo;
    private GlBuffer colorBuffer;
    private GlBuffer indirect;
    private int vao;
    private RangeAllocator arena;
    private final MaterialColors colors = new MaterialColors();

    private LodWorld world;
    private final AtomicInteger generation = new AtomicInteger();
    private final LodSelector selector = new LodSelector(LodWorld.LEVELS, DETAIL);
    private final Map<Long, Resident> resident = new HashMap<>();
    private final Set<Long> requested = new HashSet<>();
    private final Set<Long> stale = new HashSet<>();
    private final ConcurrentLinkedQueue<MeshResult> done = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> rewritten = new ConcurrentLinkedQueue<>();
    private final LongConsumer listener = rewritten::add;
    private final ExecutorService meshers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "farfield-mesher");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final ThreadLocal<BinaryGreedyMesher> mesher = ThreadLocal.withInitial(BinaryGreedyMesher::new);
    private final ThreadLocal<MeshBuffer> meshBuffer = ThreadLocal.withInitial(MeshBuffer::new);
    private int[] freeSlots = new int[0];
    private int freeSlotCount;
    private int slotCount;
    private int[] commands = new int[4 * 1024];
    private long frame;
    private long frameNanos;
    private long timedFrames;
    private int meshesUploaded;
    private int meshesEvicted;
    private long nextStats;

    private static final class Resident {
        final int slot;
        long start;
        int quadCount;
        /** Last time the selection used it: drawn, or relied on to descend to its children. */
        long lastUsed;

        Resident(int slot) {
            this.slot = slot;
        }
    }

    private record MeshResult(int generation, long key, long[] quads) {
    }

    private LodRenderer() {
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
            program = new GlProgram("lod.vert", "lod.frag");
        } catch (RuntimeException e) {
            disabled = true;
            Farfield.LOG.error("LOD rendering disabled: shader setup failed", e);
            return false;
        }
        quads = new GlBuffer(INITIAL_QUADS * 8);
        arena = new RangeAllocator(INITIAL_QUADS);
        sectionInfo = new GlBuffer(4096L * SECTION_BYTES);
        colorBuffer = new GlBuffer(4096L * 4);
        indirect = new GlBuffer(1024L * 16);
        vao = glCreateVertexArrays();
        Farfield.LOG.info("LOD renderer ready on {}, OpenGL {} ({} MB of quads to start)", glGetString(GL_RENDERER),
                glGetString(GL_VERSION), INITIAL_QUADS * 8 >> 20);
        return true;
    }

    private void renderFrame(Camera camera, Matrix4f viewRotation, Matrix4f projection) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !RenderToggle.enabled() || !init()) {
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
        frame++;
        applyRewrites();
        uploadMeshes();
        uploadColors();

        long now = System.nanoTime();
        Vec3 cam = camera.getPosition();
        List<Long> draw = new ArrayList<>();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        selector.select(cam.x, cam.y, cam.z, minY, maxY, MAX_DISTANCE, new LodSelector.Sections() {
            @Override
            public boolean exists(long key) {
                try {
                    return world.exists(key);
                } catch (IOException e) {
                    return false;
                }
            }

            @Override
            public boolean ready(long key) {
                // A section the selection relies on to descend is in use even when
                // only its children are drawn: freeing it would make its parent fall
                // back to the coarse level until it is meshed again (flicker).
                Resident r = resident.get(key);
                if (r != null) {
                    r.lastUsed = now;
                }
                return r != null;
            }
        }, draw::add, this::request);

        FrustumIntersection frustum = cullFrustum(viewRotation, projection);
        int count = 0;
        for (long key : draw) {
            Resident r = resident.get(key);
            r.lastUsed = now;
            if (r.quadCount == 0 || !visible(frustum, key, cam)) {
                continue;
            }
            if (commands.length < (count + 1) * 4) {
                commands = java.util.Arrays.copyOf(commands, commands.length * 2);
            }
            commands[count * 4] = r.quadCount * 6;
            commands[count * 4 + 1] = 1;
            commands[count * 4 + 2] = 0;
            commands[count * 4 + 3] = r.slot;
            count++;
        }
        if (frame % 60 == 0) {
            evictUnused(now);
        }
        if (count > 0) {
            draw(count, cam, viewRotation, projection, mc, skyBrightness(level, camera));
        }
        frameNanos += System.nanoTime() - frameStart;
        timedFrames++;
        if (System.nanoTime() > nextStats) {
            nextStats = System.nanoTime() + 30_000_000_000L;
            Farfield.LOG.info("LOD renderer: {} sections drawn, {} resident, {} MB of {} MB quads used, {} meshing,"
                            + " {} meshes uploaded and {} freed since last line, {} us of render-thread time per"
                            + " frame over {} frames", count, resident.size(), arena.used() * 8 >> 20,
                    arena.capacity() * 8 >> 20, requested.size(), meshesUploaded, meshesEvicted,
                    frameNanos / 1000 / Math.max(1, timedFrames), timedFrames);
            frameNanos = 0;
            timedFrames = 0;
            meshesUploaded = 0;
            meshesEvicted = 0;
        }
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
        done.clear();
        rewritten.clear();
        freeSlotCount = 0;
        slotCount = 0;
        world = next;
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
            long[] result;
            try {
                LodSection section = w.read(key);
                result = section == null ? new long[0] : mesh(w, section);
            } catch (IOException | RuntimeException e) {
                Farfield.LOG.debug("LOD mesh of {} failed", LodKey.toString(key), e);
                result = new long[0];
            }
            done.add(new MeshResult(gen, key, result));
        });
    }

    private long[] mesh(LodWorld w, LodSection section) {
        MeshBuffer out = meshBuffer.get();
        mesher.get().mesh(section, w.materials(), null, out);
        long[] all = new long[out.total()];
        int n = 0;
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            int c = out.count(d);
            System.arraycopy(out.quads(d), 0, all, n, c);
            n += c;
        }
        return all;
    }

    /**
     * Meshes again the resident sections the server rewrote; the old mesh is
     * drawn until the new one arrives. A section whose mesh is being built
     * waits for that job, which may have read the old data.
     */
    private void applyRewrites() {
        Long key;
        while ((key = rewritten.poll()) != null) {
            if (resident.containsKey(key)) {
                stale.add(key);
            }
        }
        Iterator<Long> it = stale.iterator();
        while (it.hasNext() && requested.size() < MAX_IN_FLIGHT) {
            long k = it.next();
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
            } else if (r.quadCount > 0) {
                arena.release(r.start, r.quadCount);
            }
            r.quadCount = m.quads.length;
            meshesUploaded++;
            if (r.quadCount > 0) {
                long start = arena.allocate(r.quadCount);
                if (start < 0 && growArena(r.quadCount)) {
                    start = arena.allocate(r.quadCount);
                }
                if (start < 0) {
                    r.quadCount = 0; // out of GPU budget: drawn as empty until evictions free space
                } else {
                    r.start = start;
                    quads.upload(start * 8, m.quads, m.quads.length);
                    budget -= m.quads.length;
                }
            }
            writeSection(m.key, r);
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
        int level = LodKey.level(key);
        int size = LodSection.SIZE << level;
        sectionInfo.upload((long) r.slot * SECTION_BYTES, new int[]{
                LodKey.x(key) * size, LodKey.y(key) * size, LodKey.z(key) * size, level,
                (int) r.start, r.quadCount, 0, 0});
    }

    private void evictUnused(long now) {
        Iterator<Map.Entry<Long, Resident>> it = resident.entrySet().iterator();
        while (it.hasNext()) {
            Resident r = it.next().getValue();
            if (now - r.lastUsed > EVICT_AFTER_NANOS) {
                if (r.quadCount > 0) {
                    arena.release(r.start, r.quadCount);
                }
                releaseSlot(r.slot);
                meshesEvicted++;
                it.remove();
            }
        }
    }

    private void uploadColors() {
        int first = colors.update(world.blockStates());
        if (first < 0) {
            return;
        }
        long needed = (long) colors.count() * 4;
        if (needed > colorBuffer.size()) {
            colorBuffer.grow(Math.max(needed, colorBuffer.size() * 2), (long) first * 4);
        }
        int[] all = colors.colors();
        colorBuffer.upload((long) first * 4, java.util.Arrays.copyOfRange(all, first, colors.count()));
    }

    private static FrustumIntersection cullFrustum(Matrix4f viewRotation, Matrix4f projection) {
        float m11 = Math.abs(projection.m11());
        float m00 = Math.abs(projection.m00());
        float fovY = (float) (2 * Math.atan(1 / m11)) * 1.1f;
        Matrix4f cull = new Matrix4f().perspective(fovY, m11 / m00, 1f, (float) MAX_DISTANCE * 2).mul(viewRotation);
        return new FrustumIntersection(cull);
    }

    private static boolean visible(FrustumIntersection frustum, long key, Vec3 cam) {
        int size = LodSection.SIZE << LodKey.level(key);
        float x = (float) (LodKey.x(key) * (double) size - cam.x);
        float y = (float) (LodKey.y(key) * (double) size - cam.y);
        float z = (float) (LodKey.z(key) * (double) size - cam.z);
        return frustum.testAab(x, y, z, x + size, y + size, z + size);
    }

    /** Vanilla's sky brightness (time of day, rain, thunder); 1 in dimensions without sky light. */
    private static float skyBrightness(ClientLevel level, Camera camera) {
        return level.dimensionType().hasSkyLight() ? level.getSkyDarken(camera.getPartialTickTime()) : 1f;
    }

    private void draw(int count, Vec3 cam, Matrix4f viewRotation, Matrix4f projection, Minecraft mc,
                      float brightness) {
        long bytes = (long) count * 16;
        if (bytes > indirect.size()) {
            indirect.grow(Math.max(bytes, indirect.size() * 2), 0);
        }
        indirect.upload(0, java.util.Arrays.copyOf(commands, count * 4));

        int blockX = (int) Math.floor(cam.x);
        int blockY = (int) Math.floor(cam.y);
        int blockZ = (int) Math.floor(cam.z);
        Matrix4f viewProj = new Matrix4f(projection).mul(viewRotation);
        float[] fogColor = RenderSystem.getShaderFogColor();
        float vanilla = Math.max(0, (mc.options.getEffectiveRenderDistance() - 1) * 16f);

        GlStateManager._glUseProgram(program.id());
        float[] matrix = new float[16];
        glUniformMatrix4fv(program.uniform("u_viewProj"), false, viewProj.get(matrix));
        glUniform3i(program.uniform("u_cameraBlock"), blockX, blockY, blockZ);
        glUniform3f(program.uniform("u_cameraFrac"), (float) (cam.x - blockX), (float) (cam.y - blockY),
                (float) (cam.z - blockZ));
        glUniform2f(program.uniform("u_depthRange"), 8f, (float) MAX_DISTANCE * 2);
        glUniform1f(program.uniform("u_vanillaRadius"), vanilla);
        glUniform1f(program.uniform("u_brightness"), brightness);
        glUniform4f(program.uniform("u_fogColor"), fogColor[0], fogColor[1], fogColor[2], 1f);
        glUniform2f(program.uniform("u_fog"), (float) MAX_DISTANCE * 0.6f, (float) MAX_DISTANCE);

        GlStateManager._glBindVertexArray(vao);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, quads.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, sectionInfo.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, colorBuffer.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect.id());
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        glMultiDrawArraysIndirect(GL_TRIANGLES, 0, count, 0);

        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        GlStateManager._glUseProgram(0);
        RenderSystem.enableCull();
        // Vanilla terrain draws next with its own depth range: start it clean.
        RenderSystem.clear(GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }
}
