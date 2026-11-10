package fr.enimaloc.w2d.script;

import java.util.List;

public record ScriptResult(boolean ok, List<OutgoingMessage> messages, String logs, String error) {

    public ScriptResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static ScriptResult success(List<OutgoingMessage> messages, String logs) {
        return new ScriptResult(true, messages, logs, null);
    }

    public static ScriptResult failure(String logs, String error) {
        return new ScriptResult(false, List.of(), logs, error);
    }
}
