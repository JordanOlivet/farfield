package io.lakio.farfield.mixin.iris;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the Iris mixins (shader packs, ADR-113) only when Iris is
 * installed and Voxy is not: Voxy hooks the same contract into Iris itself.
 */
public final class IrisMixinPlugin implements IMixinConfigPlugin {

    private static final boolean APPLY = present("net/irisshaders/iris/Iris.class")
            && !present("me/cortex/voxy/client/VoxyClient.class");

    private static boolean present(String resource) {
        return IrisMixinPlugin.class.getClassLoader().getResource(resource) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return APPLY;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
