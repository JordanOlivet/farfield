package io.lakio.farfield.core.agent;

import java.util.Map;

/**
 * One JSON object on one line, for the agent's outbox and the frame
 * probe's events (ADR-095, ADR-099): strings, whole numbers, decimals (NaN
 * and infinities as null), booleans and nested maps.
 */
public final class JsonLine {

    private JsonLine() {
    }

    public static String of(Map<String, ?> fields) {
        StringBuilder json = new StringBuilder(128);
        value(json, fields);
        return json.toString();
    }

    private static void field(StringBuilder json, String key, Object value) {
        string(json, key);
        json.append(':');
        value(json, value);
        json.append(',');
    }

    private static void value(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            json.append(value);
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            json.append(Double.isFinite(d) ? Double.toString(d) : "null");
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                json.append("{}");
                return;
            }
            json.append('{');
            for (Map.Entry<?, ?> e : map.entrySet()) {
                field(json, String.valueOf(e.getKey()), e.getValue());
            }
            json.setCharAt(json.length() - 1, '}');
        } else {
            string(json, value.toString());
        }
    }

    private static void string(StringBuilder json, String s) {
        json.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
