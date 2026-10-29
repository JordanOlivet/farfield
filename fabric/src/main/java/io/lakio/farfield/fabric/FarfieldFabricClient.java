package io.lakio.farfield.fabric;

import io.lakio.farfield.client.AgentClient;
import io.lakio.farfield.client.FarfieldClientCommands;
import io.lakio.farfield.client.LodClient;
import io.lakio.farfield.client.StreamClient;
import io.lakio.farfield.net.StreamPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import io.lakio.farfield.client.SettingsKey;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

public final class FarfieldFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(SettingsKey.KEY);
        ClientTickEvents.END_CLIENT_TICK.register(SettingsKey::tick);
        // Client mode (ADR-022): LODs built from the chunks a remote server sends.
        ClientChunkEvents.CHUNK_LOAD.register(LodClient::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(LodClient::onChunkUnload);
        // Streaming (ADR-068): LODs from a server with Farfield; before client mode, which it replaces.
        ClientPlayNetworking.registerGlobalReceiver(StreamPayload.TYPE,
                (payload, context) -> StreamClient.receive(payload.data()));
        ClientTickEvents.END_CLIENT_TICK.register(StreamClient::tick);
        ClientTickEvents.END_CLIENT_TICK.register(LodClient::tick);
        // Test agents drive dev clients through files (ADR-095); off unless -Dfarfield.agent=true.
        ClientTickEvents.END_CLIENT_TICK.register(AgentClient::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            StreamClient.onClientStopping();
            LodClient.onClientStopping();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                FarfieldClientCommands.register(dispatcher, FabricClientCommandSource::sendFeedback));
    }
}
