package edu.arena.server.match;

import java.util.Arrays;
import java.util.Map;

/** Anchored deadlines absorb poll jitter. A stall advances one step and resets the clock. */
final class SimulationPacer {
    private final long periodNanos;
    private long deadlineNanos;
    private long deadlineMillis;
    private long lastStepNanos;
    private long steps;
    private long elapsedNanos;
    private long resyncs;
    private boolean resynced;

    SimulationPacer(long periodMillis, long now, long millis, long initialDelayMillis) {
        periodNanos = periodMillis * 1_000_000L;
        lastStepNanos = now;
        deadlineNanos = now + initialDelayMillis * 1_000_000L;
        deadlineMillis = millis + initialDelayMillis;
    }

    boolean due(long now) {
        return now >= deadlineNanos;
    }

    void advanced(long now, long millis) {
        steps++;
        elapsedNanos += Math.max(0, now - lastStepNanos);
        lastStepNanos = now;
        long next = deadlineNanos + periodNanos;
        resynced = next <= now;
        if (next <= now) {
            next = now + periodNanos;
            resyncs++;
        }
        deadlineNanos = next;
        deadlineMillis = millis + (next - now + 999_999L) / 1_000_000L;
    }

    void resume(long now, long millis) {
        resynced = true;
        lastStepNanos = now;
        deadlineNanos = now + periodNanos;
        deadlineMillis = millis + periodNanos / 1_000_000L;
    }

    long deadlineMillis() {
        return deadlineMillis;
    }

    long steps() {
        return steps;
    }

    long elapsedMillis() {
        return elapsedNanos / 1_000_000L;
    }

    long resyncs() {
        return resyncs;
    }

    boolean resynced() {
        return resynced;
    }

    double actualHz() {
        return elapsedNanos == 0 ? 0 : steps * 1_000_000_000.0 / elapsedNanos;
    }

    Map<String, Object> metrics(ControlMailbox[] inputs, int nominalHz) {
        return Map.of(
                "simulationSteps",
                steps,
                "elapsedActiveMillis",
                elapsedMillis(),
                "actualSimulationHz",
                actualHz(),
                "nominalSimulationHz",
                nominalHz,
                "acceptedDecisions",
                Arrays.stream(inputs).mapToLong(ControlMailbox::accepted).sum(),
                "lateCommands",
                Arrays.stream(inputs).mapToLong(ControlMailbox::late).sum(),
                "duplicateCommands",
                Arrays.stream(inputs).mapToLong(ControlMailbox::duplicates).sum(),
                "schedulerResyncs",
                resyncs);
    }
}
