package io.lakio.farfield.core.shader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits a shader pack's DH programs as Iris transforms them, so they run on
 * Farfield's LOD quads instead of Distant Horizons' vertices (ADR-119).
 *
 * <p>Iris's {@code DHTerrainTransformer} (Iris 1.8.8 and 1.8.14 for MC
 * 1.21.1) adds a function {@code void _vert_init()} to the vertex shader
 * that fills globals ({@code _vert_position}, {@code _vert_color},
 * {@code _vert_normal}, {@code _vert_tex_light_coord}, {@code dhMaterialId})
 * from DH's vertex attributes, and calls it first in {@code main}. Its body
 * is cut here, leaving a declaration: a second vertex unit of ours defines
 * it from the quads. The fragment shader's {@code main} first calls
 * {@code farfield_clip()}, defined by a second fragment unit of ours.
 */
public final class DhPatch {

    /** The fragment function a patched fragment shader calls first. */
    public static final String CLIP = "farfield_clip";

    private static final Pattern VERT_INIT = Pattern.compile("void\\s+_vert_init\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

    private DhPatch() {
    }

    /**
     * The vertex shader with the body of its {@code _vert_init} cut, leaving
     * a declaration in its place.
     *
     * @throws IllegalArgumentException when it defines no {@code _vert_init}
     *                                  (Iris's transform changed)
     */
    public static String vertex(String source) {
        Matcher m = VERT_INIT.matcher(source);
        if (!m.find()) {
            throw new IllegalArgumentException("no _vert_init in the transformed DH vertex shader");
        }
        int end = closingBrace(source, m.end() - 1);
        return source.substring(0, m.start()) + "void _vert_init();" + source.substring(end + 1);
    }

    /**
     * The fragment shader with a declaration of {@link #CLIP} before its
     * {@code main} and a call to it first in {@code main}.
     *
     * @throws IllegalArgumentException when it has no {@code main}
     */
    public static String fragment(String source) {
        Matcher m = MAIN.matcher(source);
        if (!m.find()) {
            throw new IllegalArgumentException("no main in the transformed DH fragment shader");
        }
        return source.substring(0, m.start()) + "void " + CLIP + "();\n" + source.substring(m.start(), m.end())
                + "\n    " + CLIP + "();" + source.substring(m.end());
    }

    /** The index of the brace closing the one at {@code open}, comments and strings aside. */
    static int closingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int nl = s.indexOf('\n', i);
                if (nl < 0) {
                    break;
                }
                i = nl;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int close = s.indexOf("*/", i + 2);
                if (close < 0) {
                    break;
                }
                i = close + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("unbalanced braces after _vert_init");
    }
}
