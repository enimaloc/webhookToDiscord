package fr.enimaloc.w2d.script;

import com.fasterxml.jackson.databind.JsonNode;

public record OutgoingMessage(String url, JsonNode message) {
}
