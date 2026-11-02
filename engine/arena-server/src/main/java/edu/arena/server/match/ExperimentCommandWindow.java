package edu.arena.server.match;

import edu.arena.core.model.Control;

/** Teacher experiment adapter for the exact command window used by the running server. */
public final class ExperimentCommandWindow {
    private final ControlMailbox mailbox;

    public ExperimentCommandWindow(int tickRate, int periodTicks) {
        mailbox = new ControlMailbox(tickRate, periodTicks);
    }

    public void submit(int observedTick, int currentTick, Control control) {
        mailbox.submit(observedTick, currentTick, control);
    }

    public Control current(int tick) {
        return mailbox.current(tick);
    }

    public void reset(int firstTick) {
        mailbox.reset(firstTick);
    }
}
