package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.platform.Window;
import io.lakio.farfield.Farfield;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Asks for an OpenGL 4.6 core context instead of vanilla's 3.2 (the LOD
 * renderer needs 4.6, ADR-007), and falls back to vanilla's request when
 * the driver refuses, so the game always starts. NeoForge creates its own
 * context early (already the highest version) and has no such call to
 * redirect: the injection is optional.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Redirect(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J",
                    remap = false),
            require = 0)
    private long farfield$createWindow(int width, int height, CharSequence title, long monitor, long share) {
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = GLFW.glfwCreateWindow(width, height, title, monitor, share);
        if (window != 0L) {
            return window;
        }
        Farfield.LOG.warn("No OpenGL 4.6 context available; LOD rendering will be disabled");
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        return GLFW.glfwCreateWindow(width, height, title, monitor, share);
    }
}
