package io.lakio.farfield.neoforge;

import io.lakio.farfield.client.FarfieldSettingsScreen;
import io.lakio.farfield.client.SettingsKey;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only setup, kept out of {@link FarfieldNeoForge} so a dedicated server never loads screen classes. */
final class FarfieldNeoForgeClient {

    private FarfieldNeoForgeClient() {
    }

    static void init(IEventBus modBus, ModContainer container) {
        // The Mods screen's "Config" button opens the settings screen.
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new FarfieldSettingsScreen(parent));
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(SettingsKey.KEY));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> SettingsKey.tick(Minecraft.getInstance()));
    }
}
