package io.lakio.farfield.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The generator's features per decoration step and each biome's settings,
 * as its own decoration reads them: the preview runs the same features with
 * the same seeds (ADR-079).
 */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {

    @Accessor("featuresPerStep")
    Supplier<List<FeatureSorter.StepFeatureData>> farfield$featuresPerStep();

    @Accessor("generationSettingsGetter")
    Function<Holder<Biome>, BiomeGenerationSettings> farfield$generationSettingsGetter();
}
