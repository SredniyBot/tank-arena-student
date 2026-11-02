package student;

import edu.arena.core.combat.CombatBot;
import edu.arena.core.combat.CombatObservation;
import edu.arena.core.model.Control;

/** Your code starts here. The initial tank does not move, fire or enable its shield. */
public final class MyBot implements CombatBot {
    @Override
    public Control decide(CombatObservation view) {
        return Control.idle(view.me().position());
    }
}
