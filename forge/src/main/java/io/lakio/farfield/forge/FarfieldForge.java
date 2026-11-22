package io.lakio.farfield.forge;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.server.FarfieldCommands;
import io.lakio.farfield.server.LodServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Forge 47 entry point (Minecraft 1.20.1, ADR-177); NeoForge 1.20.1 loads it as well. */
@Mod(Farfield.MOD_ID)
public final class FarfieldForge {

    public FarfieldForge() {
        if (!Farfield.init()) {
            return; // beside another LOD mod (ADR-167)
        }
        ForgeNetwork.register();
        if (FMLEnvironment.dist.isClient()) {
            FarfieldForgeClient.init(FMLJavaModLoadingContext.get().getModEventBus());
        }
        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener((ServerAboutToStartEvent e) -> LodServer.onServerStarting(e.getServer()));
        bus.addListener((ServerStoppingEvent e) -> LodServer.onServerStopping(e.getServer()));
        bus.addListener((ServerStoppedEvent e) -> LodServer.onServerStopped(e.getServer()));
        bus.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) {
                LodServer.onServerTick(e.getServer());
            }
        });
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
