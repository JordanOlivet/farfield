package io.lakio.farfield.fabric;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.server.FarfieldCommands;
import io.lakio.farfield.server.LodServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class FarfieldFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Farfield.init();
        ServerLifecycleEvents.SERVER_STARTING.register(LodServer::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(LodServer::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(LodServer::onServerTick);
        ServerChunkEvents.CHUNK_LOAD.register(LodServer::onChunkLoad);
        ServerChunkEvents.CHUNK_UNLOAD.register(LodServer::onChunkUnload);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                FarfieldCommands.register(dispatcher));
    }
}
