package io.lakio.farfield.fabric;

import io.lakio.farfield.client.SettingsKey;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

public final class FarfieldFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(SettingsKey.KEY);
        ClientTickEvents.END_CLIENT_TICK.register(SettingsKey::tick);
    }
}
