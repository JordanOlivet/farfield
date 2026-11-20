package io.lakio.farfield.api.client;

import io.lakio.farfield.api.internal.ClientApiState;

/**
 * Farfield's client API: the far terrain Farfield draws, for other mods.
 *
 * <p>Call it only when Farfield is loaded (its mod id is {@code farfield}),
 * on the render thread. Compile against Farfield's jar without bundling it.
 *
 * <pre>{@code
 * // once, at client setup
 * FarfieldClientApi.requestLodDepth("mymod");
 * // each frame, after the level's terrain
 * LodFrame lods = FarfieldClientApi.lodFrame();
 * if (lods.active()) {
 *     lods.inverseViewProjection(matrix);
 *     // bind lods.depthTexture(), upload matrix, reach lods.viewDistance()
 * }
 * }</pre>
 */
public final class FarfieldClientApi {

    private FarfieldClientApi() {
    }

    /**
     * Asks Farfield to keep its LOD depth for this mod each frame. Farfield
     * shares its depth only once a mod asked (a copy of a screen-sized
     * texture per frame), and logs the mods that did.
     *
     * @param modId the asking mod's id, for the log
     */
    public static void requestLodDepth(String modId) {
        ClientApiState.request(modId);
    }

    /** This frame's LODs; never null, inactive when there is nothing to read. */
    public static LodFrame lodFrame() {
        return ClientApiState.frame();
    }
}
