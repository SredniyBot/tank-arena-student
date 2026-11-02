package edu.arena.core.engine;

import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Vec2;
import edu.arena.core.model.Wall;
import java.util.Objects;

/** Read-only map queries sharing the simulation's exact swept-circle collision geometry. */
public final class ArenaGeometry {
    private ArenaGeometry() {}

    public static boolean clearPath(ArenaMap map, Vec2 from, Vec2 to, double clearance) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!Double.isFinite(clearance) || clearance < 0) {
            throw new IllegalArgumentException("Clearance must be finite and nonnegative");
        }
        if (outside(map, from, clearance) || outside(map, to, clearance)) {
            return false;
        }
        Vec2 delta = to.subtract(from);
        for (Wall wall : map.walls()) {
            if (CollisionMath.wall(from, delta, wall, clearance) <= 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean outside(ArenaMap map, Vec2 point, double clearance) {
        return point.x() < clearance
                || point.x() > map.width() - clearance
                || point.y() < clearance
                || point.y() > map.height() - clearance;
    }
}
