package io.lakio.farfield.core.debug;

/**
 * The last frames, by frame number, so a glitch found late (a flash is
 * known only once the frame after it is in) can still be written out with
 * the frames around it (ADR-099). Each frame keeps up to {@code layers}
 * images (the end of the level, and the captures before and after the LOD
 * pass); a layer not captured is null.
 */
public final class FrameRing {

    private final int capacity;
    private final long[] numbers;
    private final int[][][] layers;

    public FrameRing(int capacity, int layerCount) {
        this.capacity = capacity;
        this.numbers = new long[capacity];
        this.layers = new int[capacity][layerCount][];
        java.util.Arrays.fill(numbers, -1);
    }

    public void put(long frame, int[]... images) {
        int slot = (int) Math.floorMod(frame, (long) capacity);
        numbers[slot] = frame;
        for (int l = 0; l < layers[slot].length; l++) {
            layers[slot][l] = l < images.length ? images[l] : null;
        }
    }

    /** A layer of a frame still held, or null. */
    public int[] get(long frame, int layer) {
        int slot = (int) Math.floorMod(frame, (long) capacity);
        return numbers[slot] == frame ? layers[slot][layer] : null;
    }
}
