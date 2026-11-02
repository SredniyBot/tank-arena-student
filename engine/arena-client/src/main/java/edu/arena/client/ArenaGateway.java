package edu.arena.client;

import edu.arena.protocol.Messages.Command;
import edu.arena.protocol.Messages.CommandReceipt;
import edu.arena.protocol.Messages.Info;
import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.Me;
import java.io.IOException;

/** The bot runner depends on match operations, not a specific network implementation. */
public interface ArenaGateway {
    Info info() throws IOException, InterruptedException;

    Me me() throws IOException, InterruptedException;

    MatchView match(String id) throws IOException, InterruptedException;

    default MatchView observation(String id) throws IOException, InterruptedException {
        return match(id);
    }

    CommandReceipt command(String id, Command command) throws IOException, InterruptedException;
}
