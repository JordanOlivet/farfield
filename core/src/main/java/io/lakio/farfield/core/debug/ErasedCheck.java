package io.lakio.farfield.core.debug;

/**
 * The renderer's own ground truth for a frame (ADR-099): three captures of
 * the game's image, before the LOD pass (sky only), right after it, and at
 * the end of the level. A pixel the LODs drew (it changed in their pass)
 * that ends the frame as it was before them (the sky again) was erased by
 * something drawn later: the game, Sodium, Iris or another mod. Vanilla
 * terrain drawn over a LOD is not flagged, since it is not the sky.
 */
public final class ErasedCheck {

    /** {@code pixels} erased pixels in the box; {@code drawn} pixels the LOD pass changed. */
    public record Result(int pixels, int drawn, int minX, int minY, int maxX, int maxY) {
    }

    private ErasedCheck() {
    }

    /**
     * @param drawnChange smallest distance ({@link FlashDetector#distance}) for a pixel to count as drawn by the LODs
     * @param skyMatch    largest distance from the pre-LOD pixel for a final pixel to count as the sky again
     */
    public static Result check(int width, int[] beforeLods, int[] afterLods, int[] end, int drawnChange, int skyMatch) {
        int pixels = 0;
        int drawn = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int i = 0; i < beforeLods.length; i++) {
            int sky = beforeLods[i];
            if (FlashDetector.distance(afterLods[i], sky) <= drawnChange) {
                continue;
            }
            drawn++;
            if (FlashDetector.distance(end[i], sky) > skyMatch) {
                continue;
            }
            pixels++;
            int x = i % width;
            int y = i / width;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return pixels == 0 ? new Result(0, drawn, 0, 0, -1, -1) : new Result(pixels, drawn, minX, minY, maxX, maxY);
    }
}
