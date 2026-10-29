package io.lakio.farfield.core.debug;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Frames as packed {@code 0xRRGGBB} arrays, to and from image files (ADR-099). */
public final class FrameImages {

    /** A frame of {@code width} by {@code height} pixels. */
    public record Frame(int width, int height, int[] rgb) {
    }

    private FrameImages() {
    }

    /**
     * Reads an image and halves it by 2x2 averages until it is at most
     * {@code maxWidth} pixels wide, as the frame probe scales the game's image
     * on the GPU: an average, so thin bands are not lost between samples.
     */
    public static Frame read(Path file, int maxWidth) throws IOException {
        BufferedImage source = ImageIO.read(file.toFile());
        if (source == null) {
            throw new IOException(file + " is not an image ImageIO reads");
        }
        int w = source.getWidth();
        int h = source.getHeight();
        int[] rgb = source.getRGB(0, 0, w, h, null, 0, w);
        while (w > maxWidth && w > 1 && h > 1) {
            int hw = w / 2;
            int hh = h / 2;
            int[] half = new int[hw * hh];
            for (int y = 0; y < hh; y++) {
                for (int x = 0; x < hw; x++) {
                    int a = rgb[2 * y * w + 2 * x];
                    int b = rgb[2 * y * w + 2 * x + 1];
                    int c = rgb[(2 * y + 1) * w + 2 * x];
                    int d = rgb[(2 * y + 1) * w + 2 * x + 1];
                    half[y * hw + x] = average(a, b, c, d);
                }
            }
            rgb = half;
            w = hw;
            h = hh;
        }
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] &= 0xFFFFFF;
        }
        return new Frame(w, h, rgb);
    }

    private static int average(int a, int b, int c, int d) {
        int r = ((a >> 16 & 0xFF) + (b >> 16 & 0xFF) + (c >> 16 & 0xFF) + (d >> 16 & 0xFF) + 2) >> 2;
        int g = ((a >> 8 & 0xFF) + (b >> 8 & 0xFF) + (c >> 8 & 0xFF) + (d >> 8 & 0xFF) + 2) >> 2;
        int bl = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF) + 2) >> 2;
        return r << 16 | g << 8 | bl;
    }

    /** Writes a frame as a PNG; with a box ({@code maxX >= minX}), its outline is drawn in magenta. */
    public static void writePng(Path file, int width, int height, int[] rgb, int minX, int minY, int maxX, int maxY)
            throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, rgb, 0, width);
        if (maxX >= minX && maxY >= minY) {
            for (int x = Math.max(0, minX - 1); x <= Math.min(width - 1, maxX + 1); x++) {
                outline(image, x, minY - 1);
                outline(image, x, maxY + 1);
            }
            for (int y = Math.max(0, minY - 1); y <= Math.min(height - 1, maxY + 1); y++) {
                outline(image, minX - 1, y);
                outline(image, maxX + 1, y);
            }
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private static void outline(BufferedImage image, int x, int y) {
        if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
            image.setRGB(x, y, 0xFF00FF);
        }
    }
}
