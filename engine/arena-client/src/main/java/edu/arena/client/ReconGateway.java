package edu.arena.client;

import edu.arena.protocol.Messages.Command;
import edu.arena.protocol.Messages.CommandReceipt;
import edu.arena.protocol.Messages.Info;
import edu.arena.protocol.Messages.Me;
import edu.arena.protocol.Messages.ReconFrame;
import java.io.IOException;

/** Personal observation transport. No operation exposes an omniscient world snapshot. */
public interface ReconGateway {
    Info info() throws IOException, InterruptedException;

    Me me() throws IOException, InterruptedException;

    ReconFrame recon(String matchId) throws IOException, InterruptedException;

    /** Wait for the first snapshot after the specified shared decision tick. */
    ReconFrame reconAfter(String matchId, int afterTick) throws IOException, InterruptedException;

    CommandReceipt command(String matchId, Command command)
            throws IOException, InterruptedException;
}
