package io.lakio.farfield.core.agent;

import java.util.Locale;

/**
 * One line of an agent command file ({@link AgentInbox}): what a test agent
 * asks the dev client to do, with no keystroke. One command per line, words
 * split on spaces; {@code #} starts a comment line.
 *
 * <pre>
 * command &lt;text&gt;                 a chat command, the leading '/' optional
 * tp &lt;x&gt; &lt;y&gt; &lt;z&gt; [&lt;yaw&gt; &lt;pitch&gt;]   teleports the player (a /tp, so operator rights)
 * look &lt;yaw&gt; &lt;pitch&gt;            turns the camera, client side
 * screenshot &lt;name&gt; [hud]       the last frame to shots/&lt;name&gt;.png, HUD hidden unless "hud"
 * state                          position, fps and the renderer's counters
 * wait stable [timeout] [hold]   until nothing is wanted in view, meshing or fetched for hold s (default 120, 2)
 * wait ticks &lt;n&gt; | wait seconds &lt;s&gt;
 * window minimize|background|restore   taskbar, behind the others (both unfocused), or in front, focused
 * record start &lt;name&gt; | record stop      records the camera to farfield/flights/&lt;name&gt;.flight (ADR-099)
 * replay &lt;name&gt; [speed &lt;f&gt;] [every &lt;n&gt;]  flies that flight again, a screenshot every n ticks
 * frames on|off                  the frame probe: short glitches written to farfield/frames/
 * quit                           closes the game as its window's close button does
 * </pre>
 */
public sealed interface AgentCommand {

    /** Longest screenshot name: letters, digits, '.', '-' and '_'. */
    int MAX_NAME_LENGTH = 64;

    record Command(String text) implements AgentCommand {
    }

    /** {@code yaw} and {@code pitch} are NaN when not given: the player keeps them. */
    record Teleport(double x, double y, double z, float yaw, float pitch) implements AgentCommand {

        /** The server command, numbers in a locale-free form. */
        public String asCommand() {
            String pos = String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f", x, y, z);
            return Float.isNaN(yaw) ? pos : pos + String.format(Locale.ROOT, " %.2f %.2f", yaw, pitch);
        }
    }

    record Look(float yaw, float pitch) implements AgentCommand {
    }

    record Screenshot(String name, boolean hud) implements AgentCommand {
    }

    record State() implements AgentCommand {
    }

    record WaitStable(double timeoutSeconds, double holdSeconds) implements AgentCommand {
    }

    record WaitTicks(int ticks) implements AgentCommand {
    }

    record WaitSeconds(double seconds) implements AgentCommand {
    }

    record Quit() implements AgentCommand {
    }

    enum WindowMode { MINIMIZE, BACKGROUND, RESTORE }

    record Window(WindowMode mode) implements AgentCommand {
    }

    /** {@code name} null for {@code record stop}. */
    record Record(String name) implements AgentCommand {
    }

    /** {@code every} 0: no screenshot. */
    record Replay(String name, double speed, int every) implements AgentCommand {
    }

    record Frames(boolean on) implements AgentCommand {
    }

    /** Whether the command needs a player in a world; the others run from any screen. */
    default boolean needsPlayer() {
        return this instanceof Command || this instanceof Teleport || this instanceof Look
                || this instanceof WaitStable || this instanceof Record r && r.name() != null || this instanceof Replay;
    }

    /**
     * Parses one line.
     *
     * @return the command, or null for a blank or comment line
     * @throws IllegalArgumentException with a message for the agent when the line is not a command
     */
    static AgentCommand parse(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        String[] w = trimmed.split("\\s+");
        String verb = w[0].toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "command" -> {
                String text = trimmed.substring(w[0].length()).strip();
                if (text.startsWith("/")) {
                    text = text.substring(1);
                }
                if (text.isEmpty()) {
                    throw new IllegalArgumentException("command needs the command text");
                }
                yield new Command(text);
            }
            case "tp" -> {
                if (w.length != 4 && w.length != 6) {
                    throw new IllegalArgumentException("tp takes <x> <y> <z> [<yaw> <pitch>]");
                }
                yield new Teleport(number(w[1]), number(w[2]), number(w[3]),
                        w.length == 6 ? (float) number(w[4]) : Float.NaN,
                        w.length == 6 ? (float) number(w[5]) : Float.NaN);
            }
            case "look" -> {
                if (w.length != 3) {
                    throw new IllegalArgumentException("look takes <yaw> <pitch>");
                }
                yield new Look((float) number(w[1]), (float) number(w[2]));
            }
            case "screenshot" -> {
                if (w.length < 2 || w.length > 3 || w.length == 3 && !w[2].equalsIgnoreCase("hud")) {
                    throw new IllegalArgumentException("screenshot takes <name> [hud]");
                }
                yield new Screenshot(name(w[1], "screenshot"), w.length == 3);
            }
            case "state" -> {
                expectWords(w, 1, "state takes no argument");
                yield new State();
            }
            case "wait" -> parseWait(w);
            case "window" -> {
                String usage = "window takes minimize, background or restore";
                expectWords(w, 2, usage);
                try {
                    yield new Window(WindowMode.valueOf(w[1].toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(usage);
                }
            }
            case "record" -> {
                if (w.length == 2 && w[1].equalsIgnoreCase("stop")) {
                    yield new Record(null);
                }
                if (w.length != 3 || !w[1].equalsIgnoreCase("start")) {
                    throw new IllegalArgumentException("record takes start <name> or stop");
                }
                yield new Record(name(w[2], "flight"));
            }
            case "replay" -> parseReplay(w);
            case "frames" -> {
                if (w.length != 2 || !w[1].equalsIgnoreCase("on") && !w[1].equalsIgnoreCase("off")) {
                    throw new IllegalArgumentException("frames takes on or off");
                }
                yield new Frames(w[1].equalsIgnoreCase("on"));
            }
            case "quit" -> {
                expectWords(w, 1, "quit takes no argument");
                yield new Quit();
            }
            default -> throw new IllegalArgumentException("unknown command '" + w[0]
                    + "' (command, tp, look, screenshot, state, wait, window, record, replay, frames, quit)");
        };
    }

    private static AgentCommand parseReplay(String[] w) {
        if (w.length < 2 || w.length % 2 != 0) {
            throw new IllegalArgumentException("replay takes <name> [speed <f>] [every <ticks>]");
        }
        double speed = 1;
        int every = 0;
        for (int i = 2; i < w.length; i += 2) {
            switch (w[i].toLowerCase(Locale.ROOT)) {
                case "speed" -> speed = positive(w[i + 1]);
                case "every" -> {
                    double n = positive(w[i + 1]);
                    if (n != Math.rint(n) || n > 100_000) {
                        throw new IllegalArgumentException("every takes a whole number of ticks");
                    }
                    every = (int) n;
                }
                default -> throw new IllegalArgumentException("replay takes <name> [speed <f>] [every <ticks>]");
            }
        }
        return new Replay(name(w[1], "flight"), speed, every);
    }

    /** A file name: letters, digits, '.', '-' and '_', not starting with '.'. */
    private static String name(String word, String what) {
        if (word.length() > MAX_NAME_LENGTH || !word.matches("[A-Za-z0-9._-]+") || word.startsWith(".")) {
            throw new IllegalArgumentException(what + " name: letters, digits, '.', '-' or '_', at most "
                    + MAX_NAME_LENGTH + ", not starting with '.'");
        }
        return word;
    }

    private static AgentCommand parseWait(String[] w) {
        String what = w.length > 1 ? w[1].toLowerCase(Locale.ROOT) : "";
        switch (what) {
            case "stable" -> {
                if (w.length > 4) {
                    throw new IllegalArgumentException("wait stable takes [timeout seconds] [hold seconds]");
                }
                double timeout = w.length > 2 ? positive(w[2]) : 120;
                double hold = w.length > 3 ? positive(w[3]) : 2;
                return new WaitStable(timeout, hold);
            }
            case "ticks" -> {
                expectWords(w, 3, "wait ticks takes <n>");
                double n = positive(w[2]);
                if (n != Math.rint(n) || n > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("wait ticks takes a whole number");
                }
                return new WaitTicks((int) n);
            }
            case "seconds" -> {
                expectWords(w, 3, "wait seconds takes <s>");
                return new WaitSeconds(positive(w[2]));
            }
            default -> throw new IllegalArgumentException("wait takes stable, ticks <n> or seconds <s>");
        }
    }

    private static void expectWords(String[] w, int count, String message) {
        if (w.length != count) {
            throw new IllegalArgumentException(message);
        }
    }

    private static double number(String word) {
        try {
            double d = Double.parseDouble(word);
            if (!Double.isFinite(d)) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: '" + word + "' (relative ~ and ^ are not accepted)");
        }
    }

    private static double positive(String word) {
        double d = number(word);
        if (d <= 0) {
            throw new IllegalArgumentException("expected a number above 0: '" + word + "'");
        }
        return d;
    }
}
