package edu.arena.core.model;

/**
 * Immutable physics rules: linear speeds in world units/second, angular speeds in radians/second,
 * radii in units.
 */
public record GameRules(
        int tickRate,
        int maxTicks,
        int hitPoints,
        double moveSpeed,
        double turnSpeed,
        double turretSpeed,
        double bulletSpeed,
        int reloadTicks,
        double tankRadius,
        double bulletRadius,
        int zoneStartTick,
        int zoneEndTick,
        int zoneDamageIntervalTicks,
        int shieldDurationTicks,
        int shieldCooldownTicks) {
    public GameRules {
        if (tickRate <= 0 || maxTicks <= 0 || hitPoints <= 0 || reloadTicks <= 0) {
            throw new IllegalArgumentException(
                    "Tick rate, duration, health and reload must be positive");
        }
        if (shieldDurationTicks <= 0 || shieldCooldownTicks < shieldDurationTicks) {
            throw new IllegalArgumentException(
                    "Shield duration must be positive and no longer than its cooldown");
        }
        if (zoneStartTick < 0
                || zoneEndTick <= zoneStartTick
                || zoneEndTick > maxTicks
                || zoneDamageIntervalTicks <= 0) {
            throw new IllegalArgumentException("Invalid zone schedule");
        }
        for (double value :
                new double[] {
                    moveSpeed, turnSpeed, turretSpeed, bulletSpeed, tankRadius, bulletRadius
                }) {
            if (!Double.isFinite(value) || value <= 0) {
                throw new IllegalArgumentException("Speeds and radii must be finite and positive");
            }
        }
    }

    /**
     * Source-compatible defaults: two simulated seconds of protection, eight between activations.
     */
    public GameRules(
            int tickRate,
            int maxTicks,
            int hitPoints,
            double moveSpeed,
            double turnSpeed,
            double turretSpeed,
            double bulletSpeed,
            int reloadTicks,
            double tankRadius,
            double bulletRadius,
            int zoneStartTick,
            int zoneEndTick,
            int zoneDamageIntervalTicks) {
        this(
                tickRate,
                maxTicks,
                hitPoints,
                moveSpeed,
                turnSpeed,
                turretSpeed,
                bulletSpeed,
                reloadTicks,
                tankRadius,
                bulletRadius,
                zoneStartTick,
                zoneEndTick,
                zoneDamageIntervalTicks,
                secondsToTicks(tickRate, 2),
                secondsToTicks(tickRate, 8));
    }

    private static int secondsToTicks(int tickRate, int seconds) {
        if (tickRate <= 0 || (long) tickRate * seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid shield schedule");
        }
        return tickRate * seconds;
    }

    /**
     * Standard three-hit shared arena; seconds is simulated time, independent of network
     * scheduling.
     */
    public static GameRules standard(int tickRate, int seconds) {
        if (tickRate <= 0 || seconds <= 0 || (long) tickRate * seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid match duration");
        }
        return new GameRules(
                tickRate,
                tickRate * seconds,
                3,
                120,
                2.8,
                5,
                420,
                Math.max(1, (int) ((4L * tickRate + 4) / 5)),
                18,
                4,
                tickRate * seconds / 3,
                Math.max(tickRate * seconds / 3 + 1, (int) (5L * tickRate * seconds / 6)),
                tickRate);
    }
}
