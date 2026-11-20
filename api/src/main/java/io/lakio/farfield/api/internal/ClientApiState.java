package io.lakio.farfield.api.internal;

import io.lakio.farfield.api.client.LodFrame;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Not API: the state behind {@link io.lakio.farfield.api.client.FarfieldClientApi},
 * written by Farfield's renderer. Other mods use the client API only.
 */
public final class ClientApiState {

    private static final Set<String> CONSUMERS = new CopyOnWriteArraySet<>();
    private static final Frame FRAME = new Frame();

    private ClientApiState() {
    }

    public static void request(String modId) {
        CONSUMERS.add(modId == null ? "unnamed" : modId);
    }

    /** The mods that asked for the LOD depth, in the order they asked. */
    public static Set<String> consumers() {
        return CONSUMERS;
    }

    public static LodFrame frame() {
        return FRAME;
    }

    /** At the start of a frame: nothing to read until the LODs drew. */
    public static void clear() {
        FRAME.active = false;
    }

    /** After the LODs drew and their depth was kept. */
    public static void publish(int depthTexture, boolean reverseZ, boolean zeroToOne,
                               float[] inverseViewProjection, float viewDistance) {
        FRAME.depthTexture = depthTexture;
        FRAME.reverseZ = reverseZ;
        FRAME.zeroToOne = zeroToOne;
        System.arraycopy(inverseViewProjection, 0, FRAME.inverse, 0, 16);
        FRAME.viewDistance = viewDistance;
        FRAME.active = true;
    }

    private static final class Frame implements LodFrame {
        private boolean active;
        private int depthTexture;
        private boolean reverseZ;
        private boolean zeroToOne;
        private final float[] inverse = new float[16];
        private float viewDistance;

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public int depthTexture() {
            return depthTexture;
        }

        @Override
        public boolean reverseZ() {
            return reverseZ;
        }

        @Override
        public boolean zeroToOne() {
            return zeroToOne;
        }

        @Override
        public void inverseViewProjection(float[] dest) {
            System.arraycopy(inverse, 0, dest, 0, 16);
        }

        @Override
        public float viewDistance() {
            return viewDistance;
        }
    }
}
