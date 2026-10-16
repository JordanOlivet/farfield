package io.lakio.farfield.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Key that opens the settings screen from the game, for installs without a
 * mod list button (Fabric without Mod Menu). Unbound by default; players set
 * it in Controls > Key Binds > Farfield. Each loader registers {@link #KEY}
 * and calls {@link #tick} at the end of every client tick.
 */
public final class SettingsKey {

    public static final KeyMapping KEY = new KeyMapping("key.farfield.settings",
            InputConstants.UNKNOWN.getValue(), "key.categories.farfield");

    private SettingsKey() {
    }

    public static void tick(Minecraft minecraft) {
        while (KEY.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new FarfieldSettingsScreen(null));
            }
        }
    }
}
