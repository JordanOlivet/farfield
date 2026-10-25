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
        int vs = compile(GL_VERTEX_SHADER, define(read(vertexResource), defines), vertexResource);
        int fs = compile(GL_FRAGMENT_SHADER, define(read(fragmentResource), defines), fragmentResource);
        id = glCreateProgram();
        glAttachShader(id, vs);
        glAttachShader(id, fs);
        glLinkProgram(id);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(id, GL_LINK_STATUS) == 0) {
            String log = glGetProgramInfoLog(id);
            glDeleteProgram(id);
            id = 0;
            throw new IllegalStateException("cannot link " + vertexResource + " + " + fragmentResource + ": " + log);
        }
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
