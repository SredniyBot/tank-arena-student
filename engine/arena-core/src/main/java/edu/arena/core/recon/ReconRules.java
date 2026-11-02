package edu.arena.core.recon;

/** Team delivery settings. All durations use simulated ticks, never wall-clock time. */
public record ReconRules(
        int teams,
        int teamSize,
        double visionRadius,
        int respawnTicks,
        int spawnProtectionTicks,
        int cargoRespawnTicks,
        double cargoSpeedFactor,
        int participantSlots) {
    /** Preserves existing equal-size rosters. */
    public ReconRules(
            int teams,
            int teamSize,
            double visionRadius,
            int respawnTicks,
            int spawnProtectionTicks,
            int cargoRespawnTicks,
            double cargoSpeedFactor) {
        this(
                teams,
                teamSize,
                visionRadius,
                respawnTicks,
                spawnProtectionTicks,
                cargoRespawnTicks,
                cargoSpeedFactor,
                Math.multiplyExact(teams, teamSize));
    }

    /** Existing callers retain full carrier speed unless they explicitly select a factor. */
    public ReconRules(
            int teams,
            int teamSize,
            double visionRadius,
            int respawnTicks,
            int spawnProtectionTicks,
            int cargoRespawnTicks) {
        this(
                teams,
                teamSize,
                visionRadius,
                respawnTicks,
                spawnProtectionTicks,
                cargoRespawnTicks,
                1.0);
    }

    public ReconRules {
        if (teams < 2
                || teams > 64
                || participantSlots < teams
                || participantSlots > 64
                || teamSize != (participantSlots + teams - 1) / teams) {
            throw new IllegalArgumentException(
                    "Expected 2–4 teams, up to 64 slots and matching maximum team size");
        }
        if (!Double.isFinite(visionRadius)
                || visionRadius <= 0
                || respawnTicks < 1
                || spawnProtectionTicks < 0
                || cargoRespawnTicks < 1) {
            throw new IllegalArgumentException("Invalid recon visibility or timing");
        }
        if (!Double.isFinite(cargoSpeedFactor) || cargoSpeedFactor <= 0 || cargoSpeedFactor > 1) {
            throw new IllegalArgumentException("Cargo speed factor must be in (0, 1]");
        }
    }

    /** Scale loaded forward/reverse movement only; turning and shield timings are unchanged. */
    public ReconRules withCargoSpeedFactor(double factor) {
        return new ReconRules(
                teams,
                teamSize,
                visionRadius,
                respawnTicks,
                spawnProtectionTicks,
                cargoRespawnTicks,
                factor,
                participantSlots);
    }

    public int capacity() {
        return participantSlots;
    }

    /** Slots are grouped by team; the host assigns identities to slots. */
    public int teamOf(int slot) {
        if (slot < 0 || slot >= capacity()) {
            throw new IllegalArgumentException("Slot outside recon roster");
        }
        int largerSize = participantSlots / teams + 1;
        int largerSlots = (participantSlots % teams) * largerSize;
        return slot < largerSlots
                ? slot / largerSize
                : participantSlots % teams + (slot - largerSlots) / (participantSlots / teams);
    }

    /** Teams have contiguous slots; capacities differ by at most one. */
    public int firstSlot(int team) {
        checkTeam(team);
        return team * (participantSlots / teams) + Math.min(team, participantSlots % teams);
    }

    public int sizeOfTeam(int team) {
        checkTeam(team);
        return participantSlots / teams + (team < participantSlots % teams ? 1 : 0);
    }

    private void checkTeam(int team) {
        if (team < 0 || team >= teams) {
            throw new IllegalArgumentException("Team outside roster");
        }
    }

    public static ReconRules standard(int tickRate) {
        if (tickRate < 1) {
            throw new IllegalArgumentException("Tick rate must be positive");
        }
        return new ReconRules(
                4,
                3,
                340,
                Math.multiplyExact(tickRate, 3),
                Math.multiplyExact(tickRate, 2),
                Math.multiplyExact(tickRate, 5));
    }
}
