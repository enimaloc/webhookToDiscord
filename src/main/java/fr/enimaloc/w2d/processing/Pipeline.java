package fr.enimaloc.w2d.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.enimaloc.w2d.discord.DiscordSender;
import fr.enimaloc.w2d.discord.DiscordUrls;
import fr.enimaloc.w2d.discord.SendResult;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.Execution;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.history.IncomingRequest;
import fr.enimaloc.w2d.json.Json;
import fr.enimaloc.w2d.script.OutgoingMessage;
import fr.enimaloc.w2d.script.ScriptRequest;
import fr.enimaloc.w2d.script.ScriptResult;
import fr.enimaloc.w2d.script.ScriptRunner;
import fr.enimaloc.w2d.store.StoreRepository;

import java.util.ArrayList;
import java.util.List;

/** Script → Discord → execution record, for one stored request. */
public final class Pipeline {
    private static final int UNKNOWN_MESSAGE = 10008;

    private final EndpointRepository endpoints;
    private final HistoryRepository history;
    private final ScriptRunner runner;
    private final DiscordSender sender;
    private final StoreRepository store;

    public Pipeline(EndpointRepository endpoints, HistoryRepository history, ScriptRunner runner,
                    DiscordSender sender, StoreRepository store) {
        this.endpoints = endpoints;
        this.history = history;
        this.runner = runner;
        this.sender = sender;
        this.store = store;
    }

    public PipelineOutcome execute(IncomingRequest req, String script, Execution.Mode mode) {
        ScriptResult raw = runner.run(script, ScriptRequest.from(req, store.all(req.endpointId())));
        // what is stored and returned is NUL-free (PostgreSQL text/jsonb reject U+0000); Discord gets the original
        ScriptResult result = sanitize(raw);
        if (!result.ok()) {
            long id = history.addExecution(req.id(), mode, Execution.Status.SCRIPT_ERROR, result.logs(),
                    null, null, result.error());
            return new PipelineOutcome(id, Execution.Status.SCRIPT_ERROR, result, List.of());
        }

        List<SendResult> sends = mode == Execution.Mode.PREVIEW ? List.of()
                : sanitizeSends(sender.sendAll(raw.messages()));
        updateStore(req.endpointId(), result.messages(), sends);
        long failed = sends.stream().filter(s -> !s.ok()).count();
        Execution.Status status = failed > 0 ? Execution.Status.SEND_ERROR : Execution.Status.SUCCESS;
        String error = failed > 0 ? failed + " of " + sends.size() + " message(s) failed to send" : null;
        JsonNode sendJson = mode == Execution.Mode.PREVIEW ? null : Json.MAPPER.valueToTree(sends);
        long id = history.addExecution(req.id(), mode, status, result.logs(), maskedMessages(result.messages()),
                sendJson, error);
        return new PipelineOutcome(id, status, result, sends);
    }

    /**
     * Applies saveId/forget of each successfully sent message, and drops the keys pointing to a message Discord no
     * longer knows. Preview has no sends, so it never writes.
     */
    private void updateStore(String endpointId, List<OutgoingMessage> messages, List<SendResult> sends) {
        for (int i = 0; i < sends.size(); i++) {
            OutgoingMessage m = messages.get(i);
            SendResult s = sends.get(i);
            if (!s.ok()) {
                String deleted = isUnknownMessage(s) ? DiscordUrls.messageId(m.url()) : null;
                if (deleted != null) {
                    store.removeValue(endpointId, deleted);
                }
                continue;
            }
            if (m.forget() != null) {
                store.remove(endpointId, m.forget());
            }
            if (m.saveId() != null && s.message() != null && s.message().hasNonNull("id")) {
                store.put(endpointId, m.saveId(), s.message().get("id").asText());
            }
        }
    }

    /** Discord's 404 "Unknown Message" (code 10008): the message was deleted. */
    static boolean isUnknownMessage(SendResult s) {
        if (s.status() != 404 || s.response() == null || s.response().isBlank()) {
            return false;
        }
        try {
            return Json.MAPPER.readTree(s.response()).path("code").asInt() == UNKNOWN_MESSAGE;
        } catch (Exception e) {
            return false;
        }
    }

    static ScriptResult sanitize(ScriptResult r) {
        List<OutgoingMessage> messages = new ArrayList<>(r.messages().size());
        for (OutgoingMessage m : r.messages()) {
            messages.add(new OutgoingMessage(m.method(), stripNul(m.url()), stripNul(m.message()),
                    stripNul(m.saveId()), stripNul(m.forget())));
        }
        return new ScriptResult(r.ok(), messages, stripNul(r.logs()), stripNul(r.error()));
    }

    private static List<SendResult> sanitizeSends(List<SendResult> sends) {
        return sends.stream().map(s -> new SendResult(s.method(), stripNul(s.url()), s.status(), s.attempts(),
                stripNul(s.response()), stripNul(s.message()), s.ok())).toList();
    }

    static String stripNul(String s) {
        return s == null || s.indexOf('\u0000') < 0 ? s : s.replace('\u0000', '\uFFFD');
    }

    /** Copy of the node with U+0000 replaced in every text value and object key. */
    static JsonNode stripNul(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            return text.indexOf('\u0000') < 0 ? node : Json.MAPPER.getNodeFactory().textNode(stripNul(text));
        }
        if (node.isArray()) {
            ArrayNode out = Json.MAPPER.createArrayNode();
            node.forEach(child -> out.add(stripNul(child)));
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = Json.MAPPER.createObjectNode();
            node.properties().forEach(e -> out.set(stripNul(e.getKey()), stripNul(e.getValue())));
            return out;
        }
        return node;
    }

    private static JsonNode maskedMessages(List<OutgoingMessage> messages) {
        ArrayNode out = Json.MAPPER.createArrayNode();
        for (OutgoingMessage m : messages) {
            out.add(describe(m));
        }
        return out;
    }

    /** History/API view of a message: URL masked, store fields only when set. */
    public static ObjectNode describe(OutgoingMessage m) {
        ObjectNode out = Json.MAPPER.createObjectNode().put("method", m.method()).put("url", DiscordUrls.mask(m.url()));
        out.set("message", m.message());
        if (m.saveId() != null) {
            out.put("saveId", m.saveId());
        }
        if (m.forget() != null) {
            out.put("forget", m.forget());
        }
        return out;
    }
}
