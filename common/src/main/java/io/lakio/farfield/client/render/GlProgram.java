package io.lakio.farfield.client.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL20C.*;

/**
 * A linked vertex + fragment program built from GLSL sources on the class
 * path ({@code /assets/farfield/shaders/}); a line {@code #include "name"}
 * is replaced by that source. Render thread only.
 */
final class GlProgram implements AutoCloseable {

    private int id;

    GlProgram(String vertexResource, String fragmentResource) {
        this(vertexResource, fragmentResource, "");
    }

    /**
     * @param defines lines inserted after the {@code #version} line of both
     *                shaders (e.g. {@code "#define DEPTH_ONLY\n"}), to build
     *                variants of one source
     */
    GlProgram(String vertexResource, String fragmentResource, String defines) {
        this(vertexResource, fragmentResource, defines, null, null);
    }

    /**
     * @param extraFragment a second fragment shader unit linked in (a shader
     *                      pack's code, ADR-113), or null
     * @param extraName     its name in error messages
     */
    GlProgram(String vertexResource, String fragmentResource, String defines, String extraFragment, String extraName) {
        this(vertexResource, fragmentResource, defines, null, extraFragment, extraName);
    }

    /**
     * @param extraVertex a second vertex shader unit linked in (a shader
     *                    pack's TAA jitter, ADR-113), or null
     */
    GlProgram(String vertexResource, String fragmentResource, String defines, String extraVertex, String extraFragment,
              String extraName) {
        int vs = compile(GL_VERTEX_SHADER, define(read(vertexResource), defines), vertexResource);
        int fs = 0;
        int extraVs = 0;
        int extraFs = 0;
        try {
            fs = compile(GL_FRAGMENT_SHADER, define(read(fragmentResource), defines), fragmentResource);
            if (extraVertex != null) {
                extraVs = compile(GL_VERTEX_SHADER, extraVertex, extraName + " (vertex)");
            }
            if (extraFragment != null) {
                extraFs = compile(GL_FRAGMENT_SHADER, extraFragment, extraName);
            }
        } catch (RuntimeException e) {
            for (int shader : new int[]{vs, fs, extraVs}) {
                if (shader != 0) {
                    glDeleteShader(shader);
                }
            }
            throw e;
        }
        id = glCreateProgram();
        int[] shaders = {vs, fs, extraVs, extraFs};
        for (int shader : shaders) {
            if (shader != 0) {
                glAttachShader(id, shader);
            }
        }
        glLinkProgram(id);
        for (int shader : shaders) {
            if (shader != 0) {
                glDeleteShader(shader);
            }
        }
        if (glGetProgrami(id, GL_LINK_STATUS) == 0) {
            String log = glGetProgramInfoLog(id);
            glDeleteProgram(id);
            id = 0;
            throw new IllegalStateException("cannot link " + vertexResource + " + " + fragmentResource + ": " + log);
        }
    }

    /** A shader source of the class path, its includes expanded. */
    static String resource(String resource) {
        return read(resource);
    }

    private static String read(String resource) {
        try (InputStream in = GlProgram.class.getResourceAsStream("/assets/farfield/shaders/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("missing shader " + resource);
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder(source.length());
            for (String line : source.split("\n", -1)) {
                String t = line.trim();
                if (t.startsWith("#include \"") && t.endsWith("\"")) {
                    out.append(read(t.substring("#include \"".length(), t.length() - 1))).append('\n');
                } else {
                    out.append(line).append('\n');
                }
            }
            return out.toString();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read shader " + resource, e);
        }
    }

    private static String define(String source, String defines) {
        if (defines.isEmpty()) {
            return source;
        }
        int line = source.indexOf('\n') + 1;
        return source.substring(0, line) + defines + source.substring(line);
    }

    private static int compile(int type, String source, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("cannot compile " + name + ": " + log);
        }
        return shader;
    }

    int id() {
        return id;
    }

    int uniform(String name) {
        return glGetUniformLocation(id, name);
    }

    @Override
    public void close() {
        if (id != 0) {
            glDeleteProgram(id);
            id = 0;
        }
    }
}
