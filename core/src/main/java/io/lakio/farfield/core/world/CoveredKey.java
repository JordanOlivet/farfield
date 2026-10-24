package io.lakio.farfield.core.world;

/**
 * Key of a coarse voxel's ground seen from above with plants on part of it
 * (ADR-060): {@code farfield:covered|<quarters>|<plant key>|<ground key>}.
 * It looks like its ground with the plant's color speckled over its top, on
 * {@code quarters} fourths of it; it has its ground's shape and class.
 *
 * @param quarters how much of the ground the plants cover, 1..4
 */
public record CoveredKey(int quarters, String plant, String ground) {

    public static final String PREFIX = "farfield:covered|";
    public static final int MAX_QUARTERS = 4;

    public String key() {
        return PREFIX + quarters + '|' + plant + '|' + ground;
    }

    public static boolean isCovered(String key) {
        return key.startsWith(PREFIX);
    }

    /** The parts of a covered key; null for any other key or a malformed one. */
    public static CoveredKey parse(String key) {
        if (!isCovered(key)) {
            return null;
        }
        int a = key.indexOf('|', PREFIX.length());
        int b = a < 0 ? -1 : key.indexOf('|', a + 1);
        if (b < 0) {
            return null;
        }
        try {
            int quarters = Integer.parseInt(key.substring(PREFIX.length(), a));
            return new CoveredKey(quarters, key.substring(a + 1, b), key.substring(b + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
