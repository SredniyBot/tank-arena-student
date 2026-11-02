package edu.arena.core.combat;

/** Personal equipment. Repair progress is measured in physics ticks, not wall time. */
public record Equipment(int ammunition, boolean turretUpgrade, int repairTicks) {
    public Equipment {
        if (ammunition < 0 || ammunition > CombatState.MAX_AMMUNITION || repairTicks < 0) {
            throw new IllegalArgumentException("Invalid equipment");
        }
    }

    public static Equipment fresh() {
        return new Equipment(CombatState.START_AMMUNITION, false, 0);
    }
}
