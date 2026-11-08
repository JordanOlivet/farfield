package io.lakio.farfield.core.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhPatchTest {

    // As Iris 1.8.14's DHTerrainTransformer leaves a pack's vertex shader (shortened).
    private static final String VERTEX = """
            #version 330 compatibility
            uniform mat4 iris_ProjectionMatrix;
            vec3 _vert_position;
            vec4 _vert_color;
            uniform vec3 modelOffset;
            void _vert_init() {    uint meta = vPosition.a;
            uint mirco = (meta & 0xFF00u) >> 8u; // mirco offset which is a xyz 2bit value
                float mx = (mirco & 1u)!=0u ? mircoOffset : 0.0; { /* } */ }
            _vert_position = (vPosition.xyz + vec3(mx, 0, mz));_vert_color = iris_color; }
            in vec4 iris_color;
            out vec4 color;
            void main() {
                _vert_init();
                color = _vert_color;
                gl_Position = iris_ProjectionMatrix * vec4(_vert_position, 1.0);
            }
            """;

    @Test
    void theVertexShaderKeepsADeclarationOfVertInitAndItsMain() {
        String patched = DhPatch.vertex(VERTEX);
        assertTrue(patched.contains("void _vert_init();"));
        assertFalse(patched.contains("vPosition.a"));
        assertFalse(patched.contains("mircoOffset : 0.0"));
        // What follows the cut body is kept whole.
        assertTrue(patched.contains("in vec4 iris_color;\nout vec4 color;\nvoid main() {\n    _vert_init();"));
        assertTrue(patched.contains("uniform vec3 modelOffset;\nvoid _vert_init();\nin vec4 iris_color;"));
    }

    @Test
    void theFragmentShaderCallsTheClipFirst() {
        String fragment = "#version 330\nin vec4 color;\nvoid main ( ) {\n    gl_FragData[0] = color;\n}\n";
        String patched = DhPatch.fragment(fragment);
        assertEquals("#version 330\nin vec4 color;\nvoid farfield_clip();\nvoid main ( ) {\n    farfield_clip();\n"
                + "    gl_FragData[0] = color;\n}\n", patched);
    }

    @Test
    void aShaderIrisDidNotTransformIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DhPatch.vertex("void main() {}"));
        assertThrows(IllegalArgumentException.class, () -> DhPatch.fragment("void farfield() {}"));
    }
}
