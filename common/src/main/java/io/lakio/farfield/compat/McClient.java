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

    /**
     * Whether the game draws the section at {@code pos}: compiled up to 1.21.10, compiled and
     * through its fade-in since (the Chunk Fade option, ADR-170), which 26.3 times itself.
     */
    public static boolean sectionCompiled(net.minecraft.client.renderer.LevelRenderer renderer,
                                          net.minecraft.core.BlockPos pos) {
        //? if >=26.3 {
        /*return renderer.isSectionCompiledAndVisible(pos, GlCompat.chunkFadeMillis(Minecraft.getInstance()));
        *///?} else if >=1.21.11 {
        /*return renderer.isSectionCompiledAndVisible(pos);
        *///?} else {
        return renderer.isSectionCompiled(pos);
        //?}
    }

    /** The camera the game renders the level from. */
    public static Camera mainCamera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera();
    }
}
