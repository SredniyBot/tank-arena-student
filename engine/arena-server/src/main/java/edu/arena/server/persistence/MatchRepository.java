package edu.arena.server.persistence;

import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.ReplayPage;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface MatchRepository {
    void save(CompletedMatch match) throws IOException;

    Optional<MatchView> view(String id);

    List<MatchView> list();

    long storedBytes();

    Optional<ReplayPage> replay(String id, int from, int limit) throws IOException;
}
