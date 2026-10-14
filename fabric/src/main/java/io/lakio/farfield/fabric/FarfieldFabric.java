package io.lakio.farfield.fabric;

import io.lakio.farfield.Farfield;
import net.fabricmc.api.ModInitializer;

public final class FarfieldFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Farfield.init();
    }
}
