package edu.arena.core.combat;

import edu.arena.core.model.Control;

/** One low-level decision per shared control window. Implement your own control architecture. */
@FunctionalInterface
public interface CombatBot {
    Control decide(CombatObservation observation);
}
