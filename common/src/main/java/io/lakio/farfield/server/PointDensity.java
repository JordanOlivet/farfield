package io.lakio.farfield.server;

/**
 * A density read at one block, as the surface search reads it: up to 26.2 a
 * density function with its column caches ({@link ColumnDensity}), since
 * 26.3 its compiled sampler in a context with caches (ADR-171).
 */
@FunctionalInterface
interface PointDensity {
    double at(int x, int y, int z);
}
