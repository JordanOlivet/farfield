package io.lakio.farfield.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The hashed world seed a server sends to clients (used for biome zoom): part of a remote world's identity. */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {

    @Accessor("biomeZoomSeed")
    long farfield$biomeZoomSeed();
}
