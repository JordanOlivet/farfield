package io.lakio.farfield.core.render;

/**
 * The LODs' depth mapping as matrices (ADR-172): {@code lod.vert} keeps the
 * game's x, y and w and replaces the clip depth with a standard OpenGL
 * perspective over the LODs' own near and far planes. These helpers build the
 * matching view-projection and its inverse, for mods that read the LOD depth.
 *
 * <p>Matrices are 16 floats, column-major (element {@code m[col * 4 + row]}),
 * the layout of JOML's {@code Matrix4f.get(float[])} and of
 * {@code glUniformMatrix4fv} with transpose false.
 */
public final class LodDepthMath {

    private LodDepthMath() {
    }

    /**
     * The LODs' view-projection: {@code projection} with its depth row
     * replaced by a {@code [near, far]} perspective, times {@code view}.
     */
    public static void viewProjection(float[] projection, float[] view, float near, float far, float[] out) {
        float[] p = projection.clone();
        // lod.vert: z_clip = w_clip * (f + n) / (f - n) - 2fn / (f - n), so the depth row is the
        // w row scaled, plus a constant (whatever the game's own depth row, reversed or not).
        float k = (far + near) / (far - near);
        for (int col = 0; col < 4; col++) {
            p[col * 4 + 2] = k * p[col * 4 + 3];
        }
        p[14] += -2f * far * near / (far - near);
        multiply(p, view, out);
    }

    /** The inverse of {@link #viewProjection}; false (and {@code out} untouched) when singular. */
    public static boolean inverseViewProjection(float[] projection, float[] view, float near, float far,
                                                float[] out) {
        float[] vp = new float[16];
        viewProjection(projection, view, near, far, vp);
        return invert(vp, out);
    }

    /** {@code out = a * b}; {@code out} may not be {@code a} or {@code b}. */
    public static void multiply(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                out[col * 4 + row] = sum;
            }
        }
    }

    /** {@code out = m^-1} in double precision; false (and {@code out} untouched) when singular. */
    public static boolean invert(float[] m, float[] out) {
        double[] a = new double[16];
        double[] inv = new double[16];
        for (int i = 0; i < 16; i++) {
            a[i] = m[i];
            inv[i] = (i % 5 == 0) ? 1.0 : 0.0;
        }
        // Gauss-Jordan on the column-major layout, with partial pivoting.
        for (int c = 0; c < 4; c++) {
            int pivot = c;
            for (int r = c + 1; r < 4; r++) {
                if (Math.abs(a[c * 4 + r]) > Math.abs(a[c * 4 + pivot])) {
                    pivot = r;
                }
            }
            double pv = a[c * 4 + pivot];
            if (Math.abs(pv) < 1e-30) {
                return false;
            }
            if (pivot != c) {
                swapRows(a, c, pivot);
                swapRows(inv, c, pivot);
            }
            for (int k = 0; k < 4; k++) {
                a[k * 4 + c] /= pv;
                inv[k * 4 + c] /= pv;
            }
            for (int r = 0; r < 4; r++) {
                if (r == c) {
                    continue;
                }
                double f = a[c * 4 + r];
                if (f != 0.0) {
                    for (int k = 0; k < 4; k++) {
                        a[k * 4 + r] -= f * a[k * 4 + c];
                        inv[k * 4 + r] -= f * inv[k * 4 + c];
                    }
                }
            }
        }
        for (int i = 0; i < 16; i++) {
            out[i] = (float) inv[i];
        }
        return true;
    }

    private static void swapRows(double[] m, int r1, int r2) {
        for (int k = 0; k < 4; k++) {
            double t = m[k * 4 + r1];
            m[k * 4 + r1] = m[k * 4 + r2];
            m[k * 4 + r2] = t;
        }
    }
}
