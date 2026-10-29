package io.lakio.farfield.client;

import io.lakio.farfield.Farfield;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.User32;

import java.util.Locale;

/**
 * Keeps an agent's dev client out of the user's way (ADR-096): with
 * {@code -Dfarfield.agent.window=minimized} the game window goes to the
 * taskbar, with {@code background} behind every other window; in both,
 * without the keyboard focus, so it never takes the mouse. The game still
 * draws into its own framebuffer, which the agent's screenshots read.
 */
public final class AgentWindow {

    public enum Mode { NORMAL, MINIMIZED, BACKGROUND }

    /** The launch mode; {@link Mode#NORMAL} without the property or the agent inbox. */
    public static final Mode LAUNCH = launchMode();

    private AgentWindow() {
    }

    private static Mode launchMode() {
        String value = System.getProperty("farfield.agent.window", "");
        if (!AgentClient.ENABLED || value.isEmpty()) {
            return Mode.NORMAL;
        }
        try {
            return Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Farfield.LOG.warn("Unknown farfield.agent.window '{}' (minimized, background): left as it is", value);
            return Mode.NORMAL;
        }
    }

    /** Before the game creates its window (vanilla's own call; NeoForge's early window is created before mods). */
    public static void beforeCreate() {
        if (LAUNCH != Mode.NORMAL) {
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        }
    }

    /** Once the game holds its window, at launch. */
    public static void afterCreate(long window) {
        if (LAUNCH != Mode.NORMAL) {
            boolean focused = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
            apply(window, LAUNCH);
            Farfield.LOG.info("Agent window: {} at launch (it had the focus: {})",
                    LAUNCH.name().toLowerCase(Locale.ROOT), focused);
        }
    }

    /**
     * Puts the window in {@code mode} without taking the focus. Leaving a
     * focused window hands the focus to the window under it, as a click on
     * the minimize button does. {@link Mode#NORMAL} restores it in front,
     * focused: the user asked to see it.
     */
    public static void apply(long window, Mode mode) {
        GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FOCUS_ON_SHOW, mode == Mode.NORMAL ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        if (mode == Mode.NORMAL) {
            GLFW.glfwRestoreWindow(window);
            GLFW.glfwFocusWindow(window);
            return;
        }
        boolean focused = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        if (Platform.get() != Platform.WINDOWS) {
            GLFW.glfwIconifyWindow(window); // no z-order call outside Windows: the taskbar for both
            return;
        }
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        if (mode == Mode.MINIMIZED) {
            User32.ShowWindow(hwnd, focused ? User32.SW_MINIMIZE : User32.SW_SHOWMINNOACTIVE);
            return;
        }
        if (focused) {
            User32.ShowWindow(hwnd, User32.SW_MINIMIZE); // hands the focus back
        }
        User32.ShowWindow(hwnd, User32.SW_SHOWNOACTIVATE);
        User32.SetWindowPos(hwnd, User32.HWND_BOTTOM, 0, 0, 0, 0,
                User32.SWP_NOMOVE | User32.SWP_NOSIZE | User32.SWP_NOACTIVATE);
    }
}
