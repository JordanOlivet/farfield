package io.lakio.farfield.neoforge;

import io.lakio.farfield.Farfield;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Farfield.MOD_ID)
public final class FarfieldNeoForge {

    public FarfieldNeoForge(IEventBus modBus) {
        Farfield.init();
    }
}
