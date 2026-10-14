package io.lakio.farfield.client.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL20C.*;

/**
 * A linked vertex + fragment program built from GLSL sources on the class
 * path ({@code /assets/farfield/shaders/}). Render thread only.
 */
final class GlProgram implements AutoCloseable {

    private int id;

    GlProgram(String vertexResource, String fragmentResource) {
        int vs = compile(GL_VERTEX_SHADER, read(vertexResource), vertexResource);
        int fs = compile(GL_FRAGMENT_SHADER, read(fragmentResource), fragmentResource);
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
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read shader " + resource, e);
        }
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
