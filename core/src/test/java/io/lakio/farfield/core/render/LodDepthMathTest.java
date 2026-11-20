package io.lakio.farfield.core.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodDepthMathTest {

    /** A column-major OpenGL perspective, as the game's projection (depth -1..1). */
    private static float[] perspective(double fovY, double aspect, double near, double far) {
        float f = (float) (1.0 / Math.tan(fovY / 2));
        float[] m = new float[16];
        m[0] = f / (float) aspect;
        m[5] = f;
        m[10] = (float) (-(far + near) / (far - near));
        m[11] = -1f;
        m[14] = (float) (-2 * far * near / (far - near));
        return m;
    }

    /** A reversed, zero-to-one perspective with an infinite far plane (26.2's kind). */
    private static float[] reversedInfinite(double fovY, double aspect, double near) {
        float f = (float) (1.0 / Math.tan(fovY / 2));
        float[] m = new float[16];
        m[0] = f / (float) aspect;
        m[5] = f;
        m[10] = 0f;
        m[11] = -1f;
        m[14] = (float) near;
        return m;
    }

    /** The camera's rotation only (the game's view matrix is camera-relative): pitch then yaw. */
    private static float[] rotation(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg);
        double p = Math.toRadians(pitchDeg);
        float[] ry = {(float) Math.cos(y), 0, (float) -Math.sin(y), 0, 0, 1, 0, 0,
                (float) Math.sin(y), 0, (float) Math.cos(y), 0, 0, 0, 0, 1};
        float[] rx = {1, 0, 0, 0, 0, (float) Math.cos(p), (float) Math.sin(p), 0,
                0, (float) -Math.sin(p), (float) Math.cos(p), 0, 0, 0, 0, 1};
        float[] out = new float[16];
        LodDepthMath.multiply(rx, ry, out);
        return out;
    }

    private static float[] apply(float[] m, double x, double y, double z, double w) {
        float[] r = new float[4];
        for (int row = 0; row < 4; row++) {
            r[row] = (float) (m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row] * w);
        }
        return r;
    }

    /** What lod.vert writes: the game's clip x, y, w, and its own depth over [near, far]. */
    private static float[] lodVertex(float[] projection, float[] view, float near, float far,
                                     double x, double y, double z) {
        float[] pv = new float[16];
        LodDepthMath.multiply(projection, view, pv);
        float[] clip = apply(pv, x, y, z, 1);
        clip[2] = clip[3] * (far + near) / (far - near) - 2f * far * near / (far - near);
        return clip;
    }

    private static void roundTrip(float[] projection) {
        float[] view = rotation(37, -12);
        float near = 64f;
        float far = 2f * 20_000f;
        float[] inverse = new float[16];
        assertTrue(LodDepthMath.inverseViewProjection(projection, view, near, far, inverse));
        double[][] points = {{300, -40, 900}, {-5_000, 120, 7_000}, {12_000, -60, -9_000}, {80, 10, 150}};
        for (double[] p : points) {
            float[] clip = lodVertex(projection, view, near, far, p[0], p[1], p[2]);
            if (clip[3] <= 0) {
                continue; // behind the camera
            }
            // What a reader samples: window depth in [0, 1], then back to NDC as the API says.
            float ndcX = clip[0] / clip[3];
            float ndcY = clip[1] / clip[3];
            float depth = clip[2] / clip[3] * 0.5f + 0.5f;
            float[] back = apply(inverse, ndcX, ndcY, depth * 2 - 1, 1);
            double distance = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            double tolerance = Math.max(0.5, distance * 2e-3); // float depth at 20 km
            assertEquals(p[0], back[0] / back[3], tolerance, "x of " + java.util.Arrays.toString(p));
            assertEquals(p[1], back[1] / back[3], tolerance, "y of " + java.util.Arrays.toString(p));
            assertEquals(p[2], back[2] / back[3], tolerance, "z of " + java.util.Arrays.toString(p));
        }
    }

    @Test
    void theInverseFindsTheCameraRelativePositionBackFromTheLodDepth() {
        roundTrip(perspective(Math.toRadians(70), 3840.0 / 2019.0, 0.05, 512));
    }

    @Test
    void theGameProjectionsOwnDepthRowDoesNotMatter() {
        // 26.2 hands the LODs a reversed, infinite projection; their depth is still their own.
        roundTrip(reversedInfinite(Math.toRadians(70), 3840.0 / 2019.0, 0.05));
    }

    @Test
    void theLodDepthRowIsTheStandardPerspectiveOverNearAndFar() {
        float near = 8f;
        float far = 4096f;
        float[] out = new float[16];
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        LodDepthMath.viewProjection(perspective(Math.toRadians(70), 1.5, 0.05, 512), identity, near, far, out);
        assertEquals(-(far + near) / (far - near), out[10], 1e-6);
        assertEquals(-2f * far * near / (far - near), out[14], 1e-3);
        assertEquals(-1f, out[11], 0f);
    }
}
