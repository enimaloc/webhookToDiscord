package fr.enimaloc.w2d.processing;

import fr.enimaloc.w2d.discord.SendResult;
import fr.enimaloc.w2d.history.Execution;
import fr.enimaloc.w2d.script.ScriptResult;

import java.util.List;

public record PipelineOutcome(long executionId, Execution.Status status, ScriptResult script, List<SendResult> sends) {
    public PipelineOutcome {
        sends = sends == null ? List.of() : List.copyOf(sends);
    }
}
