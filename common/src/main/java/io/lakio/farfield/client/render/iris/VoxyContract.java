package io.lakio.farfield.client.render.iris;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import io.lakio.farfield.mixin.iris.ShaderPackAccessor;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A shader pack's Voxy contract for one dimension (ADR-113,
 * {@code docs/analysis/shader-lod-contracts.md}): {@code voxy.json} and the
 * two fragment snippets, read through Iris's preprocessed sources.
 */
public final class VoxyContract {

    /** The files Iris must read as program starts ({@code ShaderPackSourceNamesMixin}). */
    public static final List<String> FILES = List.of("voxy.json", "voxy_opaque.glsl", "voxy_translucent.glsl");

    final String folder;
    final int[] opaqueDrawBuffers;
    final int[] translucentDrawBuffers;
    /** Iris uniforms the snippets read, declared for them. */
    final List<String> uniforms;
    /** Samplers the snippets read and their GLSL types, declared for them. */
    final Map<String, String> samplers;
    /**
     * The translucent snippet's blending per draw buffer location, -1 for
     * the default: "off" or four GL factor names (source and destination
     * color, then alpha).
     */
    final Map<Integer, String> blending;
    final String opaque;
    final String translucent;
    /** The body of a GLSL function returning the pack's TAA jitter in NDC, or null. */
    final String taaOffset;
    /** The pack's TAAU render scale (x, y): the LODs draw into that part of the viewport. */
    final float[] renderScale;

    private VoxyContract(String folder, int[] opaqueDrawBuffers, int[] translucentDrawBuffers, List<String> uniforms,
                         Map<String, String> samplers, Map<Integer, String> blending, String opaque, String translucent,
                         String taaOffset, float[] renderScale) {
        this.folder = folder;
        this.opaqueDrawBuffers = opaqueDrawBuffers;
        this.translucentDrawBuffers = translucentDrawBuffers;
        this.uniforms = uniforms;
        this.samplers = samplers;
        this.blending = blending;
        this.opaque = opaque;
        this.translucent = translucent;
        this.taaOffset = taaOffset;
        this.renderScale = renderScale;
    }

    /**
     * The contract of {@code pack} in {@code dimension}, or null when the pack
     * has none there.
     *
     * @throws IllegalStateException when {@code voxy.json} cannot be read
     */
    static VoxyContract read(ShaderPack pack, NamespacedId dimension) {
        Function<AbsolutePackPath, String> sources = ((ShaderPackAccessor) pack).farfield$sourceProvider();
        // As Iris picks a dimension's program folder (ShaderPack.getProgramSet).
        Map<NamespacedId, String> dimensions = ((ShaderPackAccessor) pack).farfield$dimensionMap();
        String folder = dimensions.get(dimension);
        if (folder == null) {
            folder = dimensions.getOrDefault(new NamespacedId("*", "*"), "");
        }
        String base = folder.isEmpty() ? "/" : "/" + folder + "/";
        String json = source(sources, base + "voxy.json");
        String opaque = source(sources, base + "voxy_opaque.glsl");
        if ((json == null || opaque == null) && !folder.isEmpty()) {
            base = "/";
            folder = "";
            json = source(sources, "/voxy.json");
            opaque = source(sources, "/voxy_opaque.glsl");
        }
        if (json == null || opaque == null) {
            return null;
        }
        String translucent = source(sources, base + "voxy_translucent.glsl");
        // Not a JSON document as a whole: Photon includes its settings into a
        // string field, quotes and all. Only the contract's keys are read, each
        // value on its own.
        JsonObject root = new JsonObject();
        try {
            for (String key : KEYS) {
                JsonElement value = value(json, key);
                if (value != null) {
                    root.add(key, value);
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot read " + base + "voxy.json: " + e.getMessage(), e);
        }
        List<String> uniforms = new ArrayList<>();
        if (root.get("uniforms") instanceof JsonArray list) {
            for (JsonElement e : list) {
                uniforms.add(e.getAsString());
            }
        }
        Map<String, String> samplers = new LinkedHashMap<>();
        JsonElement s = root.get("samplers");
        if (s instanceof JsonArray list) {
            for (JsonElement e : list) {
                samplers.put(e.getAsString(), "sampler2D");
            }
        } else if (s instanceof JsonObject map) {
            for (Map.Entry<String, JsonElement> e : map.entrySet()) {
                samplers.put(e.getKey(), e.getValue().getAsString());
            }
        }
        Map<Integer, String> blending = new LinkedHashMap<>();
        if (root.get("blending") instanceof JsonObject map) {
            for (Map.Entry<String, JsonElement> e : map.entrySet()) {
                try {
                    blending.put(Integer.parseInt(e.getKey().trim()), e.getValue().getAsString().trim());
                } catch (NumberFormatException ignored) {
                    // not a draw buffer location
                }
            }
        }
        return new VoxyContract(folder, drawBuffers(root, "opaqueDrawBuffers"), drawBuffers(root, "translucentDrawBuffers"),
                uniforms, samplers, blending, opaque, translucent, taaOffset(root), renderScale(root));
    }

    private static final List<String> KEYS = List.of("opaqueDrawBuffers", "translucentDrawBuffers", "uniforms",
            "samplers", "blending", "excludeLodsFromVanillaDepth", "taaOffset", "renderScale");

    private static String taaOffset(JsonObject root) {
        JsonElement e = root.get("taaOffset");
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String body = e.getAsString().trim();
        if (body.isEmpty()) {
            return null;
        }
        // A function body; a bare expression is returned as is.
        return body.startsWith("{") ? body : "{ return (" + body + "); }";
    }

    private static float[] renderScale(JsonObject root) {
        float[] scale = {1f, 1f};
        if (root.get("renderScale") instanceof JsonArray list && list.size() >= 2) {
            for (int i = 0; i < 2; i++) {
                try {
                    float s = list.get(i).getAsFloat();
                    if (s > 0f && s <= 1f) {
                        scale[i] = s;
                    }
                } catch (RuntimeException e) {
                    // not a number: a macro the preprocessor left, full scale
                }
            }
        }
        return scale;
    }

    /**
     * The value of {@code "key":} in {@code json}, an array, an object or a
     * plain value, parsed leniently on its own (unquoted keys such as
     * Photon's {@code -1 : "off"}); null when the key is absent.
     */
    static JsonElement value(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:").matcher(json);
        if (!m.find()) {
            return null;
        }
        int i = m.end();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int end = i;
        if (i < json.length() && json.charAt(i) == '"') {
            // A string, line breaks included (Photon's taaOffset is GLSL over
            // several lines, no JSON string): read to its closing quote.
            StringBuilder text = new StringBuilder();
            for (end = i + 1; end < json.length() && json.charAt(end) != '"'; end++) {
                char c = json.charAt(end);
                if (c == '\\' && end + 1 < json.length()) {
                    c = json.charAt(++end);
                }
                text.append(c);
            }
            return new com.google.gson.JsonPrimitive(text.toString());
        }
        if (i < json.length() && (json.charAt(i) == '[' || json.charAt(i) == '{')) {
            int depth = 0;
            boolean quoted = false;
            for (end = i; end < json.length(); end++) {
                char c = json.charAt(end);
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted && (c == '[' || c == '{')) {
                    depth++;
                } else if (!quoted && (c == ']' || c == '}') && --depth == 0) {
                    end++;
                    break;
                }
            }
        } else {
            while (end < json.length() && ",}\n".indexOf(json.charAt(end)) < 0) {
                end++;
            }
        }
        // A trailing comma before a closing bracket is no JSON either.
        String text = json.substring(i, end).replaceAll(",(\\s*[\\]}])", "$1");
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(true);
        return JsonParser.parseReader(reader);
    }

    private static String source(Function<AbsolutePackPath, String> sources, String path) {
        return sources.apply(AbsolutePackPath.fromAbsolutePath(path));
    }

    private static int[] drawBuffers(JsonObject root, String key) {
        if (!(root.get(key) instanceof JsonArray list) || list.isEmpty()) {
            return new int[]{0};
        }
        int[] buffers = new int[list.size()];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = list.get(i).getAsInt();
        }
        return buffers;
    }
}
