package io.lakio.farfield.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.agent.JsonLine;
import io.lakio.farfield.core.debug.ColumnFlicker;
import io.lakio.farfield.core.debug.ErasedCheck;
import io.lakio.farfield.core.debug.FlashDetector;
import io.lakio.farfield.core.debug.FrameImages;
import io.lakio.farfield.core.debug.FrameRing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Watches every frame for short glitches (ADR-099), off unless turned on
 * ({@code /farfieldclient debug frames}, the agent inbox, or
 * {@code -Dfarfield.frames=true}). Three captures of the game's image per
 * frame: before the LOD pass (the sky), after it, and at the end of the
 * level. Each is scaled down by 2x2 averages to at most 512 pixels wide on
 * the GPU and read back a few frames later through pixel buffers, so the
 * game never waits for them; the render thread only copies them out, and a
 * thread of its own analyses them and writes the files. {@link FlashDetector}
 * finds areas that change for one or two frames and come back;
 * {@link ErasedCheck} finds LOD pixels that end the frame as the sky;
 * {@link ColumnFlicker} watches what the renderer draws in each chunk column
 * and finds states that last one or two frames, from the renderer's own
 * state. The last frames are kept, and an event writes the frames around it
 * as PNGs, with one JSON line, in {@code <game dir>/farfield/frames/<session>/}.
 */
public final class FrameProbe {

    /**
     * Tuned on the user's annotated recording of 2026-10-28 (ADR-099): 10 of
     * its 13 glitches found, with 40 other frames flagged in 350 (some at the
     * game's render edge, a known separate glitch, the rest not judged).
     */
    public static final int CHANGE = 14;
    public static final int SETTLE = 6;
    /** Smallest glitch area, as a share of the frame's pixels (60 of 479 x 257). */
    public static final double MIN_AREA = 0.0005;
    private static final int ERASE_DRAWN = 30;
    private static final int ERASE_SKY = 12;
    private static final int MAX_WIDTH = 512;
    private static final int IN_FLIGHT = 4;
    /** Frames waiting for the analysis thread; past it a frame is dropped (the detectors start again). */
    private static final int MAX_QUEUED = 6;
    private static final int LAYERS = 3;
    private static final int END = 0;
    private static final int BACKGROUND = 1;
    private static final int LODS = 2;
    private static final int RING = 48;
    private static final int MAX_DUMPS_PER_MINUTE = 20;
    private static final long STATS_NANOS = 10_000_000_000L;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static boolean enabled = Boolean.getBoolean("farfield.frames");
    private static FrameProbe instance;

    // Render thread.
    private final int sourceWidth;
    private final int sourceHeight;
    private final int width;
    private final int height;
    /** Halving chain: level i is the source halved i + 1 times; the last level is the capture size. */
    private final int[] chainFbo;
    private final int[] chainTex;
    private final int[] chainW;
    private final int[] chainH;
    private final int[][] pbo = new int[IN_FLIGHT][LAYERS];
    private final long[] fence = new long[IN_FLIGHT];
    private final long[] slotFrame = new long[IN_FLIGHT];
    private final int[] slotLayers = new int[IN_FLIGHT];
    private final double[][] slotCamera = new double[IN_FLIGHT][5];
    private final double[] camera = new double[5];
    private int slot;
    private long frame;
    private int capturedLayers;
    private final ColumnFlicker columns = new ColumnFlicker();

    // Shared.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "farfield-frame-probe");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicLong totalEvents = new AtomicLong();
    /** Render thread time the probe took, for the stat line. */
    private final AtomicLong renderNanos = new AtomicLong();
    private final AtomicInteger renderFrames = new AtomicInteger();
    /** Readback arrays the analysis thread hands back: no garbage per frame. */
    private final ArrayBlockingQueue<int[]> rawPool = new ArrayBlockingQueue<>((MAX_QUEUED + IN_FLIGHT + 1) * LAYERS);
    private final Path folder;

    // Analysis thread.
    private final FlashDetector flashes;
    /** The last end-of-level images; the captures around the LOD pass are kept for their own frame only. */
    private final FrameRing ring = new FrameRing(RING, 1);
    private int[] background;
    private int[] lods;
    private final double[][] ringCamera = new double[RING][5];
    /** Events whose frames are not all read back yet: written once they are. */
    private final List<Dump> pending = new ArrayList<>();
    private int dumpsThisMinute;
    private long minuteStart = System.nanoTime();
    private long nextStats = System.nanoTime() + STATS_NANOS;
    private int analysed;
    private int flashCount;
    private int erasedCount;
    private int columnCount;
    private boolean firstWritten;

    private record Dump(String name, long first, long last, long flagFirst, long flagLast, int minX, int minY,
                        int maxX, int maxY) {
    }

    private FrameProbe(Minecraft mc, int sourceWidth, int sourceHeight) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        int levels = 1;
        while ((sourceWidth >> levels) > MAX_WIDTH) {
            levels++;
        }
        chainFbo = new int[levels];
        chainTex = new int[levels];
        chainW = new int[levels];
        chainH = new int[levels];
        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        for (int i = 0; i < levels; i++) {
            chainW[i] = Math.max(1, sourceWidth >> (i + 1));
            chainH[i] = Math.max(1, sourceHeight >> (i + 1));
            chainTex[i] = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, chainTex[i]);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, chainW[i], chainH[i]);
            chainFbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, chainFbo[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, chainTex[i], 0);
        }
        glBindTexture(GL_TEXTURE_2D, previousTexture);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
        width = chainW[levels - 1];
        height = chainH[levels - 1];
        int previousPack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        for (int s = 0; s < IN_FLIGHT; s++) {
            for (int l = 0; l < LAYERS; l++) {
                pbo[s][l] = glGenBuffers();
                glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[s][l]);
                glBufferData(GL_PIXEL_PACK_BUFFER, (long) width * height * 4, GL_STREAM_READ);
            }
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPack);
        flashes = new FlashDetector(width, height, CHANGE, SETTLE, minArea());
        folder = mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve("farfield").resolve("frames")
                .resolve(LocalDateTime.now().format(STAMP));
        Farfield.LOG.info("Frame probe on: {}x{} captures of a {}x{} frame, events in {}", width, height,
                sourceWidth, sourceHeight, folder);
    }

    private int minArea() {
        return Math.max(8, (int) (MIN_AREA * width * height));
    }

    public static boolean enabled() {
        return enabled;
    }

    /** @return a line for the player */
    public static String setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            FrameProbe p = instance;
            instance = null;
            if (p != null) {
                p.close();
                Farfield.LOG.info("Frame probe off: {} events in {}", p.totalEvents.get(), p.folder);
                return "Frame probe off: " + p.totalEvents.get() + " events written to " + p.folder;
            }
            return "Frame probe off.";
        }
        return "Frame probe on: glitch events and their frames go to farfield/frames/.";
    }

    /** Events found since the probe was turned on (the agent reports it after a replay). */
    public static long events() {
        FrameProbe p = instance;
        return p == null ? 0 : p.totalEvents.get();
    }

    /** The column watch for the frame being drawn, begun; null when the probe is off. */
    public static ColumnFlicker beginColumns() {
        FrameProbe p = instance;
        if (!enabled || p == null) {
            return null;
        }
        p.columns.begin(p.frame);
        return p.columns;
    }

    /** The column watch of this frame is complete. */
    public static void endColumns() {
        FrameProbe p = instance;
        if (enabled && p != null) {
            List<ColumnFlicker.Flicker> flickers = p.columns.end();
            if (!flickers.isEmpty()) {
                p.noteCamera();
                double[] cam = p.camera.clone();
                p.worker.execute(() -> p.columnEvents(flickers, cam));
            }
        }
    }

    /** Before the LOD pass (the sky is drawn). */
    public static void beforeLods() {
        FrameProbe p = current();
        if (p != null) {
            p.capture(BACKGROUND);
        }
    }

    /** After the LOD pass. */
    public static void afterLods() {
        FrameProbe p = instance;
        if (enabled && p != null) {
            p.capture(LODS);
        }
    }

    /** End of the level, before the hand and the HUD. */
    public static void endOfLevel() {
        FrameProbe p = current();
        if (p != null) {
            p.capture(END);
            p.finishFrame();
        }
    }

    /** The probe for the current window size, made or remade as needed; null when off. */
    private static FrameProbe current() {
        if (!enabled) {
            return null;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        FrameProbe p = instance;
        if (p != null && (p.sourceWidth != main.width || p.sourceHeight != main.height)) {
            p.close();
            p = null;
        }
        if (p == null) {
            p = new FrameProbe(Minecraft.getInstance(), main.width, main.height);
            instance = p;
        }
        return p;
    }

    // ---------------------------------------------------------------- render thread

    private void noteCamera() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            camera[0] = p.getX();
            camera[1] = p.getEyeY();
            camera[2] = p.getZ();
            camera[3] = p.getYRot();
            camera[4] = p.getXRot();
        }
    }

    private void capture(int layer) {
        if (fence[slot] != 0) {
            return; // this slot's readback is still in flight: the frame is dropped
        }
        long start = System.nanoTime();
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousPack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        glDisable(GL_SCISSOR_TEST);
        int fromFbo = main.frameBufferId;
        for (int i = 0; i < chainFbo.length; i++) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fromFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, chainFbo[i]);
            // Halving with linear filtering averages each 2x2 block: no aliasing of thin bands.
            glBlitFramebuffer(0, 0, chainW[i] * 2, chainH[i] * 2, 0, 0, chainW[i], chainH[i],
                    GL_COLOR_BUFFER_BIT, GL_LINEAR);
            fromFbo = chainFbo[i];
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fromFbo);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[slot][layer]);
        // BGRA as reversed bytes: each pixel reads back as one 0xAARRGGBB int.
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPack);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
        if (scissor) {
            glEnable(GL_SCISSOR_TEST);
        }
        if (layer == BACKGROUND || layer == END && capturedLayers == 0) {
            noteCamera();
        }
        capturedLayers |= 1 << layer;
        renderNanos.addAndGet(System.nanoTime() - start);
    }

    private void finishFrame() {
        long start = System.nanoTime();
        renderFrames.incrementAndGet();
        if ((capturedLayers & 1 << END) != 0) {
            slotFrame[slot] = frame;
            slotLayers[slot] = capturedLayers;
            System.arraycopy(camera, 0, slotCamera[slot], 0, 5);
            fence[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            slot = (slot + 1) % IN_FLIGHT;
        } else {
            dropped.incrementAndGet();
        }
        capturedLayers = 0;
        frame++;
        // Copy out every finished slot, oldest first; the analysis thread does the rest.
        for (int k = 0; k < IN_FLIGHT; k++) {
            int s = (slot + k) % IN_FLIGHT;
            if (fence[s] == 0) {
                continue;
            }
            int status = glClientWaitSync(fence[s], 0, 0);
            if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) {
                break;
            }
            glDeleteSync(fence[s]);
            fence[s] = 0;
            if (queued.get() >= MAX_QUEUED) {
                dropped.incrementAndGet();
                continue;
            }
            int[][] raw = new int[LAYERS][];
            for (int l = 0; l < LAYERS; l++) {
                if ((slotLayers[s] & 1 << l) != 0) {
                    raw[l] = copyOut(pbo[s][l]);
                }
            }
            long f = slotFrame[s];
            double[] cam = slotCamera[s].clone();
            queued.incrementAndGet();
            worker.execute(() -> {
                try {
                    analyse(f, raw, cam);
                } catch (RuntimeException e) {
                    Farfield.LOG.warn("Frame probe: analysing frame {} failed", f, e);
                } finally {
                    queued.decrementAndGet();
                    for (int[] r : raw) {
                        if (r != null) {
                            rawPool.offer(r);
                        }
                    }
                }
            });
        }
        renderNanos.addAndGet(System.nanoTime() - start);
    }

    /** A pixel buffer's ints, as GL holds them (bottom row first). */
    private int[] copyOut(int buffer) {
        int previousPack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
        ByteBuffer data = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (long) width * height * 4, GL_MAP_READ_BIT);
        int[] raw = rawPool.poll();
        if (raw == null) {
            raw = new int[width * height];
        }
        if (data != null) {
            data.order(ByteOrder.nativeOrder()).asIntBuffer().get(raw);
            glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPack);
        return raw;
    }

    private void close() {
        for (int s = 0; s < IN_FLIGHT; s++) {
            if (fence[s] != 0) {
                glDeleteSync(fence[s]);
                fence[s] = 0;
            }
            for (int l = 0; l < LAYERS; l++) {
                glDeleteBuffers(pbo[s][l]);
            }
        }
        for (int i = 0; i < chainFbo.length; i++) {
            glDeleteFramebuffers(chainFbo[i]);
            glDeleteTextures(chainTex[i]);
        }
        worker.shutdown(); // the frames already queued are still analysed and written
    }

    // ---------------------------------------------------------------- analysis thread

    private void analyse(long f, int[][] raw, double[] cam) {
        int[][] images = new int[LAYERS][];
        // The end image goes to the ring: take the array of the frame it pushes out.
        images[END] = topDownRgb(raw[END], ring.get(f - RING, 0));
        if (raw[BACKGROUND] != null && raw[LODS] != null) {
            background = topDownRgb(raw[BACKGROUND], background);
            lods = topDownRgb(raw[LODS], lods);
            images[BACKGROUND] = background;
            images[LODS] = lods;
        }
        ring.put(f, images[END]);
        System.arraycopy(cam, 0, ringCamera[(int) (f % RING)], 0, 5);
        analysed++;
        if (!firstWritten && images[BACKGROUND] != null) {
            // What the probe sees, once per session: a check of the captures themselves.
            firstWritten = true;
            try {
                Files.createDirectories(folder);
                png("first-background", images[BACKGROUND], -1, 0, 0, -1, -1);
                png("first-lods", images[LODS], -1, 0, 0, -1, -1);
                png("first-end", images[END], -1, 0, 0, -1, -1);
            } catch (IOException | RuntimeException e) {
                Farfield.LOG.warn("Frame probe: writing the first frame failed", e);
            }
        }
        for (FlashDetector.Flash flash : flashes.push(f, images[END])) {
            flashCount++;
            event(flash.frames() == 1 ? "flash1" : "flash2", flash.frame(), flash.frames(), flash.pixels(),
                    new int[]{flash.minX(), flash.minY(), flash.maxX(), flash.maxY()}, ringCamera(flash.frame()),
                    Map.of(), null);
        }
        if (images[BACKGROUND] != null && images[LODS] != null) {
            ErasedCheck.Result erased = ErasedCheck.check(width, images[BACKGROUND], images[LODS], images[END],
                    ERASE_DRAWN, ERASE_SKY);
            if (erased.pixels() >= minArea()) {
                erasedCount++;
                event("erased", f, 1, erased.pixels(),
                        new int[]{erased.minX(), erased.minY(), erased.maxX(), erased.maxY()}, cam, Map.of(), images);
            }
        }
        writePending(f);
        long now = System.nanoTime();
        if (now > nextStats) {
            nextStats = now + STATS_NANOS;
            int lost = dropped.getAndSet(0);
            long spent = renderNanos.getAndSet(0);
            int drawn = renderFrames.getAndSet(0);
            Farfield.LOG.info("Frame probe: {} flashes, {} erased LOD areas and {} frames with flickering columns"
                            + " in 10 s ({} frames analysed, {} dropped; {} us per frame on the render thread)",
                    flashCount, erasedCount, columnCount, analysed, lost, spent / 1000 / Math.max(1, drawn));
            flashCount = 0;
            erasedCount = 0;
            columnCount = 0;
            analysed = 0;
        }
    }

    /** GL's bottom-up 0xAARRGGBB rows as top-down 0xRRGGBB pixels, into {@code into} when it is given. */
    private int[] topDownRgb(int[] raw, int[] into) {
        int[] rgb = into != null && into.length == raw.length ? into : new int[raw.length];
        for (int y = 0; y < height; y++) {
            int from = (height - 1 - y) * width;
            int to = y * width;
            for (int x = 0; x < width; x++) {
                rgb[to + x] = raw[from + x] & 0xFFFFFF;
            }
        }
        return rgb;
    }

    private double[] ringCamera(long f) {
        return ringCamera[(int) (f % RING)].clone();
    }

    private void columnEvents(List<ColumnFlicker.Flicker> flickers, double[] cam) {
        columnCount++;
        long first = Long.MAX_VALUE;
        int frames = 0;
        List<String> examples = new ArrayList<>();
        for (ColumnFlicker.Flicker f : flickers) {
            first = Math.min(first, f.frame());
            frames = Math.max(frames, f.frames());
            if (examples.size() < 3) {
                examples.add("chunk " + f.chunkX() + " " + f.chunkZ() + ": " + ColumnFlicker.describe(f.before())
                        + " > " + ColumnFlicker.describe(f.brief()) + " for " + f.frames() + " > "
                        + ColumnFlicker.describe(f.after()));
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("columns", flickers.size());
        extra.put("examples", String.join("; ", examples));
        event("column", first, frames, flickers.size(), null, cam, extra, null);
    }

    /** Writes the events whose frames are all read back by frame {@code f}. */
    private void writePending(long f) {
        Iterator<Dump> it = pending.iterator();
        while (it.hasNext()) {
            Dump d = it.next();
            if (d.last() > f) {
                continue;
            }
            it.remove();
            try {
                for (long k = d.first(); k <= d.last(); k++) {
                    boolean flagged = k >= d.flagFirst() && k <= d.flagLast();
                    png(d.name(), ring.get(k, 0), (int) (k - d.first()), flagged ? d.minX() : 0,
                            flagged ? d.minY() : 0, flagged ? d.maxX() : -1, flagged ? d.maxY() : -1);
                }
            } catch (IOException | RuntimeException e) {
                Farfield.LOG.warn("Frame probe: writing the frames of {} failed", d.name(), e);
            }
        }
    }

    /**
     * One event: a JSON line now, its frames (the one before to the one after)
     * once they are read back. {@code box} is the flagged area in capture
     * pixels, or null; {@code images}, the frame's three captures for an
     * erased area (written at once), or null.
     */
    private void event(String kind, long f, int frames, int pixels, int[] box, double[] cam, Map<String, ?> extra,
                       int[][] images) {
        totalEvents.incrementAndGet();
        long now = System.nanoTime();
        if (now - minuteStart > 60_000_000_000L) {
            minuteStart = now;
            dumpsThisMinute = 0;
        }
        boolean dump = dumpsThisMinute < MAX_DUMPS_PER_MINUTE;
        String name = String.format(Locale.ROOT, "%06d-%s", f, kind);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("frame", f);
        line.put("kind", kind);
        line.put("frames", frames);
        line.put("pixels", pixels);
        if (box != null) {
            line.put("box", box[0] + "," + box[1] + "," + box[2] + "," + box[3]);
        }
        line.put("size", width + "x" + height);
        line.put("camera", String.format(Locale.ROOT, "%.1f %.1f %.1f %.1f %.1f", cam[0], cam[1], cam[2], cam[3], cam[4]));
        line.putAll(extra);
        line.put("written", dump);
        try {
            Files.createDirectories(folder);
            if (dump) {
                dumpsThisMinute++;
                if (images != null) {
                    png(name + "-background", images[BACKGROUND], -1, 0, 0, -1, -1);
                    png(name + "-lods", images[LODS], -1, 0, 0, -1, -1);
                    png(name + "-end", images[END], -1, box[0], box[1], box[2], box[3]);
                } else {
                    int[] b = box == null ? new int[]{0, 0, -1, -1} : box;
                    pending.add(new Dump(name, f - 1, f + frames, f, f + frames - 1, b[0], b[1], b[2], b[3]));
                }
            }
            try (Writer out = Files.newBufferedWriter(folder.resolve("events.jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(JsonLine.of(line));
                out.write("\n");
            }
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("Frame probe: writing event {} failed", name, e);
        }
        Farfield.LOG.info("Frame probe: {} at frame {}, {} {}{}{}", kind, f, pixels, box == null ? "columns" : "pixels",
                extra.containsKey("examples") ? " (" + extra.get("examples") + ")" : "",
                dump ? "" : " (frames not written: over the minute's limit)");
    }

    private void png(String name, int[] rgb, int index, int minX, int minY, int maxX, int maxY) throws IOException {
        if (rgb == null) {
            return;
        }
        String file = index < 0 ? name + ".png" : String.format(Locale.ROOT, "%s-%d.png", name, index);
        FrameImages.writePng(folder.resolve(file), width, height, rgb, minX, minY, maxX, maxY);
    }
}
