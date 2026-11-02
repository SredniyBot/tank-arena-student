package edu.arena.server.persistence;

import edu.arena.core.model.GameState;
import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.RecordedTurn;
import java.util.List;

/** Complete replay plus accepted inputs; no bearer tokens or pending commands. */
public record CompletedMatch(MatchView match, List<GameState> frames, List<RecordedTurn> turns) {
    public CompletedMatch {
        frames = List.copyOf(frames);
        turns = List.copyOf(turns);
    }
}
