package io.lakio.farfield.forge;

import io.lakio.farfield.client.FarfieldClientCommands;
import io.lakio.farfield.client.FarfieldSettingsScreen;
import io.lakio.farfield.client.FlightRecorder;
import io.lakio.farfield.client.LodClient;
import io.lakio.farfield.client.SettingsKey;
import io.lakio.farfield.client.StreamClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

/** Client-only setup, kept out of {@link FarfieldForge} so a dedicated server never loads screen classes. */
final class FarfieldForgeClient {

    private FarfieldForgeClient() {
    }

    static void init(IEventBus modBus) {
        // The Mods screen's "Config" button opens the settings screen.
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new FarfieldSettingsScreen(parent)));
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(SettingsKey.KEY));
        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            SettingsKey.tick(mc);
            // Streaming (ADR-068) before client mode, which it replaces.
            StreamClient.tick(mc);
            LodClient.tick(mc);
            // Flight recording for replays (ADR-099).
            FlightRecorder.tick(mc);
        });
        // Client mode (ADR-022): LODs built from the chunks a remote server sends.
        bus.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel() instanceof ClientLevel level && e.getChunk() instanceof LevelChunk chunk) {
                LodClient.onChunkLoad(level, chunk);
            }
        });
        bus.addListener((ChunkEvent.Unload e) -> {
            if (e.getLevel() instanceof ClientLevel level && e.getChunk() instanceof LevelChunk chunk) {
                LodClient.onChunkUnload(level, chunk);
            }
        });
        bus.addListener((GameShuttingDownEvent e) -> {
            StreamClient.onClientStopping();
            LodClient.onClientStopping();
        });
        bus.addListener((RegisterClientCommandsEvent e) -> FarfieldClientCommands.register(
                e.getDispatcher(), (source, line) -> source.sendSuccess(() -> line, false)));
    }
}
