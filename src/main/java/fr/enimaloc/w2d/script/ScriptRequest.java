package fr.enimaloc.w2d.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import fr.enimaloc.w2d.history.IncomingRequest;
import fr.enimaloc.w2d.json.Json;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ScriptRequest(String method, Map<String, List<String>> headers, Map<String, List<String>> query,
                            String body, JsonNode json) {

    private static final ObjectReader STRICT_READER =
            Json.MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static ScriptRequest from(IncomingRequest in) {
        String body = decodeUtf8(in.body());
        return new ScriptRequest(in.method(),
                in.headers() == null ? Map.of() : in.headers(),
                parseQuery(in.query()),
                body,
                parseJson(body));
    }

    private static String decodeUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            // unreachable with REPLACE actions
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static JsonNode parseJson(String body) {
        if (body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = STRICT_READER.readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static Map<String, List<String>> parseQuery(String query) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        String q = query.startsWith("?") ? query.substring(1) : query;
        for (String part : q.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = urlDecode(eq < 0 ? part : part.substring(0, eq));
            String value = eq < 0 ? "" : urlDecode(part.substring(eq + 1));
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
