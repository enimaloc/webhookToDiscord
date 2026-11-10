package fr.enimaloc.w2d.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

import java.util.List;

/** Script → Discord → execution record, for one stored request. */
public final class Pipeline {
    private final EndpointRepository endpoints;
    private final HistoryRepository history;
    private final ScriptRunner runner;
    private final DiscordSender sender;

    public Pipeline(EndpointRepository endpoints, HistoryRepository history, ScriptRunner runner,
                    DiscordSender sender) {
        this.endpoints = endpoints;
        this.history = history;
        this.runner = runner;
        this.sender = sender;
    }

    public PipelineOutcome execute(IncomingRequest req, String script, Execution.Mode mode) {
        ScriptResult result = runner.run(script, ScriptRequest.from(req));
        if (!result.ok()) {
            long id = history.addExecution(req.id(), mode, Execution.Status.SCRIPT_ERROR, result.logs(),
                    null, null, result.error());
            return new PipelineOutcome(id, Execution.Status.SCRIPT_ERROR, result, List.of());
        }

        List<SendResult> sends = mode == Execution.Mode.PREVIEW ? List.of() : sender.sendAll(result.messages());
        long failed = sends.stream().filter(s -> !s.ok()).count();
        Execution.Status status = failed > 0 ? Execution.Status.SEND_ERROR : Execution.Status.SUCCESS;
        String error = failed > 0 ? failed + " of " + sends.size() + " message(s) failed to send" : null;
        JsonNode sendJson = mode == Execution.Mode.PREVIEW ? null : Json.MAPPER.valueToTree(sends);
        long id = history.addExecution(req.id(), mode, status, result.logs(), maskedMessages(result.messages()),
                sendJson, error);
        return new PipelineOutcome(id, status, result, sends);
    }

    private static JsonNode maskedMessages(List<OutgoingMessage> messages) {
        ArrayNode out = Json.MAPPER.createArrayNode();
        for (OutgoingMessage m : messages) {
            out.addObject().put("url", DiscordUrls.mask(m.url())).set("message", m.message());
        }
        return out;
    }
}
