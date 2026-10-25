package io.lakio.farfield.neoforge;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.server.FarfieldCommands;
import io.lakio.farfield.server.LodServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(Farfield.MOD_ID)
public final class FarfieldNeoForge {

    public FarfieldNeoForge(IEventBus modBus, ModContainer container) {
        Farfield.init();
        modBus.addListener(NeoForgeNetwork::register);
        if (FMLEnvironment.dist.isClient()) {
            FarfieldNeoForgeClient.init(modBus, container);
        }
        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerAboutToStartEvent e) -> LodServer.onServerStarting(e.getServer()));
        bus.addListener((ServerStoppingEvent e) -> LodServer.onServerStopping(e.getServer()));
        bus.addListener((ServerTickEvent.Post e) -> LodServer.onServerTick(e.getServer()));
        bus.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk chunk) {
                LodServer.onChunkLoad(level, chunk);
            }
        });
        bus.addListener((ChunkEvent.Unload e) -> {
            if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk chunk) {
                LodServer.onChunkUnload(level, chunk);
            }
        });
        bus.addListener((RegisterCommandsEvent e) -> FarfieldCommands.register(e.getDispatcher()));
    }
}
