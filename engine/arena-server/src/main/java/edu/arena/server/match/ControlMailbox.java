package edu.arena.server.match;

import edu.arena.core.model.Control;
import edu.arena.protocol.Messages.CommandReceipt;

/** One immutable decision per shared window; arrival time never changes its execution ticks. */
final class ControlMailbox {
    private final int period;
    private int minimumTick;
    private int sourceTick = -1;
    private Control submitted;
    private Control active;
    private int activeUntil;
    private long accepted;
    private long late;
    private long duplicates;

    ControlMailbox(int tickRate) {
        this(tickRate, periodTicks(tickRate));
    }

    ControlMailbox(int tickRate, int controlPeriodTicks) {
        if (tickRate < 1 || controlPeriodTicks < 1) {
            throw new IllegalArgumentException("Invalid period");
        }
        period = controlPeriodTicks;
    }

    static int periodTicks(int tickRate) {
        return Math.max(1, (tickRate + 3) / 4);
    }

    CommandReceipt submit(int observedTick, int currentTick, Control control) {
        // A lost acknowledgement may be retried after the response window. It cannot renew input.
        if (observedTick >= minimumTick && observedTick == sourceTick) {
            if (!control.equals(submitted)) {
                throw new ApplicationFailure(
                        409, "COMMAND_CONFLICT", "Решение на этот такт уже принято.");
            }
            duplicates++;
            return receipt(observedTick, true);
        }
        if (observedTick < minimumTick
                || observedTick < 0
                || observedTick % period != 0
                || observedTick > currentTick
                || currentTick - observedTick >= period) {
            late++;
            throw new ApplicationFailure(
                    409, "STALE_TICK", "Получите снимок общего такта управления.");
        }
        // Preserve the previous window even when the next request arrives before current().
        promote(currentTick);
        sourceTick = observedTick;
        submitted = control;
        accepted++;
        return receipt(observedTick, false);
    }

    private CommandReceipt receipt(int tick, boolean duplicate) {
        return new CommandReceipt(tick, tick + period, tick + 2 * period, duplicate);
    }

    long accepted() {
        return accepted;
    }

    long late() {
        return late;
    }

    long duplicates() {
        return duplicates;
    }

    private void promote(int tick) {
        if (submitted != null && tick >= sourceTick + period) {
            active = submitted;
            activeUntil = sourceTick + 2 * period;
        }
    }

    Control current(int tick) {
        promote(tick);
        return tick < activeUntil ? active : null;
    }

    void reset(int firstTick) {
        minimumTick = firstTick;
        sourceTick = -1;
        submitted = null;
        active = null;
        activeUntil = 0;
    }
}
