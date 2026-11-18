package io.lakio.farfield.client.sodium;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.SettingsModel;
import io.lakio.farfield.lod.RenderSettings;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Farfield's settings in Sodium's video settings screen (Sodium 0.8 on):
 * one page per page of {@link SettingsModel}, as Farfield's own screen, which
 * stays the one shown without Sodium. Sodium calls the bindings when the
 * player applies, then {@link RenderSettings#save}. Sodium 0.6 and installs
 * without Sodium never load this class: Fabric declares it under the {@code
 * sodium:config_api_user} entrypoint, NeoForge through the annotation.
 */
@ConfigEntryPointForge("farfield")
public final class FarfieldSodiumConfig implements ConfigEntryPoint {

    private static final String PREFIX = "farfield.options.";

    private final StorageEventHandler save = RenderSettings::save;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ModOptionsBuilder mod = builder.registerOwnModOptions();
        int pages = 0;
        int options = 0;
        for (SettingsModel.Page page : SettingsModel.pages()) {
            pages++;
            options += page.settings().size();
            OptionGroupBuilder group = builder.createOptionGroup();
            for (SettingsModel.Setting setting : page.settings()) {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("farfield",
                        setting.key().startsWith(PREFIX) ? setting.key().substring(PREFIX.length()) : setting.key());
                if (setting instanceof SettingsModel.Toggle t) {
                    group.addOption(builder.createBooleanOption(id)
                            .setName(Component.translatable(t.key()))
                            .setTooltip(t.tooltip())
                            .setStorageHandler(save)
                            .setBinding(t.set()::accept, () -> t.get().getAsBoolean())
                            .setDefaultValue(t.defaultValue()));
                } else if (setting instanceof SettingsModel.Slider s) {
                    group.addOption(builder.createIntegerOption(id)
                            .setName(Component.translatable(s.key()))
                            .setTooltip(s.tooltip())
                            .setStorageHandler(save)
                            .setRange(s.min(), s.max(), 1)
                            .setValueFormatter(s.label()::apply)
                            .setBinding(s.set()::accept, () -> s.get().getAsInt())
                            .setDefaultValue(s.defaultStep()));
                }
            }
            mod.addPage(builder.createOptionPage()
                    .setName(Component.translatable(page.key()))
                    .addOptionGroup(group));
        }
        Farfield.LOG.info("Settings in Sodium's video settings: {} pages, {} options", pages, options);
    }
}
