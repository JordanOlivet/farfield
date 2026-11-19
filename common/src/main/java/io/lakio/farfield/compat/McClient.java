package io.lakio.farfield.compat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The client's side of {@link Mc}: game calls that differ between Minecraft
 * versions (ADR-165), kept apart so a dedicated server never loads a client
 * class through them.
 */
public final class McClient {
    private McClient() {
    }

    /** A message of the client itself in the chat. */
    public static void clientMessage(Minecraft mc, Component message) {
        //? if >=26.2 {
        /*mc.gui.hud.getChat().addClientSystemMessage(message);
        *///?} else if >=26.1 {
        /*mc.gui.getChat().addClientSystemMessage(message);
        *///?} else {
        mc.gui.getChat().addMessage(message);
        //?}
    }

    /** The camera the game renders the level from. */
    public static Camera mainCamera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera();
    }
}
