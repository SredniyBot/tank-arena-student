package edu.arena.core.bot;

import edu.arena.core.engine.ArenaGeometry;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Tank;
import edu.arena.core.model.Vec2;
import java.util.Comparator;
import java.util.Optional;

/** Small geometry helpers for student bots; angles use radians and distances use world units. */
public final class BotGeometry {
    private BotGeometry() {}

    /** Line-of-sight or movement test using the same wall geometry as the simulation. */
    public static boolean clearPath(ArenaMap map, Vec2 from, Vec2 to, double clearance) {
        return ArenaGeometry.clearPath(map, from, to, clearance);
    }

    /** Selects a visible opponent; geometry breaks ties without favouring participant slots. */
    public static Optional<Tank> nearestVisibleOpponent(Observation observation) {
        return observation.opponents().stream()
                .filter(
                        tank ->
                                clearPath(
                                        observation.map(),
                                        observation.me().position(),
                                        tank.position(),
                                        observation.rules().bulletRadius()))
                .min(
                        Comparator.comparingDouble(
                                        (Tank tank) ->
                                                observation
                                                        .me()
                                                        .position()
                                                        .distance(tank.position()))
                                .thenComparingDouble(tank -> tank.position().x())
                                .thenComparingDouble(tank -> tank.position().y()));
    }

    /**
     * First waypoint along a short route around cover. Bots still decide when and whom to pursue.
     */
    public static Vec2 navigateToward(Observation observation, Vec2 destination) {
        return CoverNavigation.waypoint(observation, destination);
    }

    /** Signed shortest rotation from current to target, in radians. */
    public static double angleDifference(double target, double current) {
        if (!Double.isFinite(target) || !Double.isFinite(current)) {
            throw new IllegalArgumentException("Angles must be finite");
        }
        return Math.IEEEremainder(target - current, Math.PI * 2);
    }

    /**
     * Converts a target point into a turn command held for one control interval. The rotation
     * budget is turnSpeed times the interval's duration in game seconds; for one physics step it is
     * turnSpeed / tickRate. Earlier pending controls can still change the starting heading.
     */
    public static double turnToward(
            double heading, Vec2 from, Vec2 target, double maxRadiansPerInterval) {
        if (!Double.isFinite(maxRadiansPerInterval) || maxRadiansPerInterval <= 0) {
            throw new IllegalArgumentException("Turn rate must be positive");
        }
        Vec2 delta = target.subtract(from);
        if (delta.length() < 1e-12) {
            return 0;
        }
        return Math.clamp(
                angleDifference(Math.atan2(delta.y(), delta.x()), heading) / maxRadiansPerInterval,
                -1,
                1);
    }

    /** Turn over the entire control interval. Pending earlier commands can still change heading. */
    public static double turnToward(Observation observation, Vec2 target) {
        return turnToward(
                observation.me().heading(),
                observation.me().position(),
                target,
                observation.rules().turnSpeed()
                        * observation.timing().durationSeconds(observation.rules().tickRate()));
    }

    /** Optional nearest-target convenience; an empty field produces a harmless aim point. */
    public static Vec2 predictedAim(Observation observation) {
        return observation
                .nearestOpponent()
                .map(target -> predictedAim(observation, target))
                .orElse(observation.me().position());
    }

    public static Vec2 interceptAim(Observation observation) {
        return observation
                .nearestOpponent()
                .map(target -> interceptAim(observation, target))
                .orElse(observation.me().position());
    }

    /** Projects a desired position into the usable safe circle, with a margin for steering. */
    public static Vec2 insideZone(Observation observation, Vec2 desired) {
        Vec2 centre = new Vec2(observation.map().width() / 2, observation.map().height() / 2);
        double radius =
                Math.max(
                        0,
                        observation.state().safeRadius() - observation.rules().tankRadius() - 35);
        Vec2 offset = desired.subtract(centre);
        if (offset.length() <= radius) {
            return desired;
        }
        return offset.length() < 1e-12
                ? centre
                : centre.add(offset.scale(radius / offset.length()));
    }

    /** First-order lead: assumes the opponent continues at its current observed velocity. */
    public static Vec2 predictedAim(Observation observation, Tank target) {
        double flightTime =
                observation.me().position().distance(target.position())
                        / observation.rules().bulletSpeed();
        return target.position().add(target.velocity().scale(flightTime));
    }

    /**
     * Intercepts constant-velocity motion by solving |relativePosition + velocity * t| =
     * bulletSpeed * t.
     */
    public static Vec2 interceptAim(Observation observation, Tank target) {
        Vec2 relative = target.position().subtract(observation.me().position());
        Vec2 velocity = target.velocity();
        double speed = observation.rules().bulletSpeed();
        double a = velocity.x() * velocity.x() + velocity.y() * velocity.y() - speed * speed;
        double b = 2 * (relative.x() * velocity.x() + relative.y() * velocity.y());
        double c = relative.x() * relative.x() + relative.y() * relative.y();
        double discriminant = b * b - 4 * a * c;
        double time = Double.POSITIVE_INFINITY;
        if (Math.abs(a) < 1e-9) {
            if (b < 0) {
                time = -c / b;
            }
        } else if (discriminant >= 0) {
            double first = (-b - Math.sqrt(discriminant)) / (2 * a);
            double second = (-b + Math.sqrt(discriminant)) / (2 * a);
            if (first >= 0) {
                time = first;
            }
            if (second >= 0) {
                time = Math.min(time, second);
            }
        }
        return Double.isFinite(time)
                ? target.position().add(velocity.scale(time))
                : target.position();
    }

    /**
     * Only accelerates forward when the desired direction is reasonably close to the hull heading.
     */
    public static double forwardThrottle(Observation observation, Vec2 destination) {
        Vec2 delta = destination.subtract(observation.me().position());
        if (delta.length() < 1e-12) {
            return 0;
        }
        double error =
                angleDifference(Math.atan2(delta.y(), delta.x()), observation.me().heading());
        return Math.max(0, Math.cos(error));
    }
}
