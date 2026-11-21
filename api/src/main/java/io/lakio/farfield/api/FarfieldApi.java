package io.lakio.farfield.api;

/**
 * Entry point of the Farfield API.
 *
 * <p>Public since API version 1: {@link io.lakio.farfield.api.client.FarfieldClientApi}
 * shares the far terrain's depth with other mods (ADR-173). The
 * {@code io.lakio.farfield.api.internal} package is not API.
 */
public final class FarfieldApi {

    /** Bumped on every incompatible change to this module. */
    public static final int API_VERSION = 1;

    private FarfieldApi() {
    }
}
