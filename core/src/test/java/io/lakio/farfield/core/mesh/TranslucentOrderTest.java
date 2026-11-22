package io.lakio.farfield.core.mesh;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslucentOrderTest {

    /** The plane a face lies in, in voxels, along its axis. */
    private static double plane(long q) {
        int d = Quad.direction(q);
        int axis = Quad.axis(d);
        int coord = axis == 0 ? Quad.x(q) : axis == 1 ? Quad.y(q) : Quad.z(q);
        if (!Quad.positive(d)) {
            return coord;
        }
        return coord + (axis == 1 ? Quad.fill(q) / 8.0 : 1.0);
    }

    @Test
    void everyGroupIsFarToNearForAnyCameraThatSeesItsFaces() {
        Random random = new Random(176);
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            long[] quads = new long[200];
            for (int i = 0; i < quads.length; i++) {
                quads[i] = Quad.pack(random.nextInt(32), random.nextInt(32), random.nextInt(32), 1, 1, d,
                        1 + random.nextInt(4), -1, 15, 0, 1 + random.nextInt(8));
            }
            // A margin of untouched entries on both sides: only the range moves.
            long first = quads[0];
            long last = quads[199];
            TranslucentOrder.sortFarToNear(quads, 1, 199, d);
            assertEquals(first, quads[0]);
            assertEquals(last, quads[199]);
            for (double camera = -40; camera <= 72; camera += 0.5) {
                double previous = Double.MAX_VALUE;
                for (int i = 1; i < 199; i++) {
                    double p = plane(quads[i]);
                    boolean front = Quad.positive(d) ? camera > p : camera < p;
                    if (!front) {
                        continue; // a back face: culled, its place does not matter
                    }
                    double distance = Math.abs(camera - p);
                    assertTrue(distance <= previous, "direction " + d + ", camera " + camera + ", quad " + i);
                    previous = distance;
                }
            }
        }
    }

    @Test
    void aWaterTopGoesUnderTheIceTopOfTheVoxelBeside() {
        long ice = Quad.pack(3, 10, 4, 1, 1, Quad.POS_Y, 2, -1, 15, 0, 8);
        long water = Quad.pack(4, 10, 4, 1, 1, Quad.POS_Y, 1, -1, 15, 0, 7);
        long[] quads = {ice, water};
        TranslucentOrder.sortFarToNear(quads, 0, 2, Quad.POS_Y);
        assertArrayEquals(new long[] {water, ice}, quads);
    }

    @Test
    void sideFacesFirstThenTheHorizontalOnesFacingTheCameraLast() {
        assertArrayEquals(new int[] {Quad.NEG_X, Quad.POS_X, Quad.NEG_Z, Quad.POS_Z, Quad.NEG_Y, Quad.POS_Y},
                TranslucentOrder.directions(false));
        assertArrayEquals(new int[] {Quad.NEG_X, Quad.POS_X, Quad.NEG_Z, Quad.POS_Z, Quad.POS_Y, Quad.NEG_Y},
                TranslucentOrder.directions(true));
    }
}
