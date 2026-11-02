package edu.arena.core.bot;

/**
 * Shared response window and the exact simulation interval affected by one decision. The epoch
 * deadline is a server-clock estimate at publication, not a wall-clock guarantee under a stall;
 * acceptance and execution are determined by shared simulation ticks.
 */
public record ControlTiming(
        int observedTick,
        int applyAtTick,
        int expiresAtTick,
        int controlPeriodTicks,
        long responseDeadlineEpochMillis) {
    public ControlTiming {
        if (observedTick < 0
                || applyAtTick < observedTick
                || controlPeriodTicks < 1
                || expiresAtTick - applyAtTick != controlPeriodTicks) {
            throw new IllegalArgumentException("Invalid control timing");
        }
    }

    /** Local engine callers traditionally evaluate a fresh decision before every step. */
    public static ControlTiming immediate(int tick) {
        return new ControlTiming(tick, tick, Math.addExact(tick, 1), 1, 0);
    }

    public double durationSeconds(int tickRate) {
        if (tickRate < 1) {
            throw new IllegalArgumentException("Tick rate must be positive");
        }
        return (double) controlPeriodTicks / tickRate;
    }
}
