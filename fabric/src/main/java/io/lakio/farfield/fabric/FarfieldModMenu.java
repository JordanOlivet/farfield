package io.lakio.farfield.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.lakio.farfield.client.FarfieldSettingsScreen;

/** Mod Menu's "configure" button opens the settings screen; loaded only when Mod Menu is installed. */
public final class FarfieldModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return FarfieldSettingsScreen::new;
    }
}
