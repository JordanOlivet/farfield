package io.lakio.farfield.neoforge;

import io.lakio.farfield.client.FarfieldSettingsScreen;
import io.lakio.farfield.client.FarfieldClientCommands;
import io.lakio.farfield.client.LodClient;
import io.lakio.farfield.client.SettingsKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

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
        // Client mode (ADR-022): LODs built from the chunks a remote server sends.
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel() instanceof ClientLevel level && e.getChunk() instanceof LevelChunk chunk) {
                LodClient.onChunkLoad(level, chunk);
            }
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload e) -> {
            if (e.getLevel() instanceof ClientLevel level && e.getChunk() instanceof LevelChunk chunk) {
                LodClient.onChunkUnload(level, chunk);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> LodClient.tick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent e) -> LodClient.onClientStopping());
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> FarfieldClientCommands.register(
                e.getDispatcher(), (source, line) -> source.sendSuccess(() -> line, false)));
    }
}
