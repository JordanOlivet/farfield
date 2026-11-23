package io.lakio.farfield.client.render;

import io.lakio.farfield.Farfield;
import org.lwjgl.glfw.GLFW;

/**
 * Asks for an OpenGL 4.6 core context for the game's window (the LOD renderer
 * needs 4.6, ADR-007): a hidden 4.6 window probes the driver; when it works,
 * the 4.6 hints stay for the game's own window, otherwise the 3.2 hints of
 * vanilla are put back, so the game always starts. Called right before the
 * game's window is created, after its hints are set: by {@code WindowMixin},
 * and on Forge 47 by the forge project's hook of Forge's hand-off.
 */
public final class GlContextProbe {

    private GlContextProbe() {
    }

    public static void requestGl46() {
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
