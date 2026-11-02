package edu.arena.core.bot;

import edu.arena.core.model.Control;

/**
 * Student extension point. Decide from one immutable shared tick; return one command without doing
 * network I/O.
 */
@FunctionalInterface
public interface Bot {
    Control decide(Observation observation);
}
