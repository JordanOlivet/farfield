package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.platform.Window;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.AgentWindow;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Asks for an OpenGL 4.6 core context instead of vanilla's 3.2 (the LOD
 * renderer needs 4.6, ADR-007). Just before vanilla creates its window, a
 * hidden 4.6 window probes the driver; when it works, the 4.6 hints stay for
 * vanilla's own call, otherwise vanilla's 3.2 request is left as it was, so
 * the game always starts.
 *
 * <p>An injection rather than a redirect of {@code glfwCreateWindow}:
 * Sodium redirects that call (its NVIDIA workarounds), and two redirects of
 * one call conflict, one of them being skipped depending on load order.
 * NeoForge creates its own context early (already the highest version) and
 * hands it over through {@code ImmediateWindowHandler}; without its early
 * window ({@code earlyWindowControl = false} in {@code fml.toml}, which an
 * agent's run sets, ADR-096) that call creates the window with the hints set
 * here, so the probe runs there too. Both injections are optional.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Inject(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J",
                    remap = false),
            require = 0)
    private void farfield$requestGl46(CallbackInfo ci) {
        AgentWindow.beforeCreate(); // an agent's dev client opens without the focus (ADR-096)
        farfield$probeGl46();
    }

    @Inject(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow("
                            + "Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;"
                            + "Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J",
                    remap = false),
            require = 0)
    private void farfield$requestGl46WithoutEarlyWindow(CallbackInfo ci) {
        if (!farfield$neoForgeEarlyWindow()) {
            AgentWindow.beforeCreate();
            farfield$probeGl46();
        }
    }

    /**
     * Whether NeoForge's early window holds the context already, read from
     * {@code fml.toml} through FML's public config API: its window handler's
     * fields sit in a module that is not open to mods.
     */
    private static boolean farfield$neoForgeEarlyWindow() {
        try {
            Class<?> config = Class.forName("net.neoforged.fml.loading.FMLConfig");
            Class<?> value = Class.forName("net.neoforged.fml.loading.FMLConfig$ConfigValue");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object key = Enum.valueOf((Class) value, "EARLY_WINDOW_CONTROL");
            boolean early = (Boolean) config.getMethod("getBoolConfigValue", value).invoke(null, key);
            if (!early) {
                Farfield.LOG.info("NeoForge early window off: OpenGL 4.6 probed for the game window");
            }
            return early;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Farfield.LOG.warn("Could not read NeoForge's early window setting; its window is left as it is", e);
            return true;
        }
    }

    private static void farfield$probeGl46() {
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long probe = GLFW.glfwCreateWindow(16, 16, "Farfield OpenGL 4.6 probe", 0L, 0L);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE);
        if (probe != 0L) {
            GLFW.glfwDestroyWindow(probe);
            Farfield.LOG.info("OpenGL 4.6 context available: requested for the game window");
            return;
        }
        Farfield.LOG.warn("No OpenGL 4.6 context available; LOD rendering will be disabled");
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
    }
}
