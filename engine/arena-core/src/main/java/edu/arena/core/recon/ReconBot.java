package edu.arena.core.recon;

import edu.arena.core.model.Control;

/** One decision from a local observation. The student owns every behavioral choice. */
@FunctionalInterface
public interface ReconBot {
    Control decide(ReconObservation observation);
}
