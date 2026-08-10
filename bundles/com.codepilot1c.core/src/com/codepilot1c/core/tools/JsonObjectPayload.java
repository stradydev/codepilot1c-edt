package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Parses a nested-object tool argument that arrived as a JSON <em>string</em>
 * instead of a native object.
 *
 * <p>Most MCP payloads are serialized somewhere along the way, so a caller
 * passing {@code payload} as text is ordinary rather than wrong;
 * {@code edt_validate_request} used to answer a bare {@code payload must be an
 * object} and spend the round-trip. Reported 2026-08-08 in
 * {@code 2026-08-08-edt-validate-request-no-container-semantic-check.md}
 * (ask 2).</p>
 *
 * <p>Numbers are converted the way {@link ToolArgumentParser} converts them —
 * an integral value becomes {@code Integer}/{@code Long}, never
 * {@code Double}. This matters: downstream normalizers read some values through
 * {@code String.valueOf}, where a Gson-default {@code Double} would turn
 * {@code length: 150} into {@code "150.0"} and change the metadata written.</p>
 */
public final class JsonObjectPayload {

    private JsonObjectPayload() { }

    /**
     * Parses {@code text} as a JSON object.
     *
     * @param text the serialized object; may be {@code null}
     * @return the parsed map, or {@code null} when {@code text} is not a JSON
     *         object (malformed, or a valid JSON array/number/string)
     */
    public static Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            return null;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return null;
        }
        return toMap(parsed.getAsJsonObject());
    }

    private static Map<String, Object> toMap(JsonObject object) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            map.put(entry.getKey(), toJava(entry.getValue()));
        }
        return map;
    }

    private static List<Object> toList(JsonArray array) {
        List<Object> list = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            list.add(toJava(element));
        }
        return list;
    }

    private static Object toJava(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return toMap(element.getAsJsonObject());
        }
        if (element.isJsonArray()) {
            return toList(element.getAsJsonArray());
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            double value = primitive.getAsDouble();
            if (value == Math.floor(value) && !Double.isInfinite(value)) {
                long asLong = primitive.getAsLong();
                if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
                    return (int) asLong;
                }
                return asLong;
            }
            return value;
        }
        return primitive.getAsString();
    }
}
