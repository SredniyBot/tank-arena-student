package edu.arena.core.bot;

import edu.arena.core.model.Control;
import edu.arena.core.model.Projectile;
import edu.arena.core.model.Vec2;

/**
 * Orbits at medium range, returns to safety, and shields against an imminent incoming projectile.
 */
public final class StrafeBot implements Bot {
    @Override
    public Control decide(Observation observation) {
        var nearest =
                BotGeometry.nearestVisibleOpponent(observation).or(observation::nearestOpponent);
        if (observation.me().health() == 0 || nearest.isEmpty()) {
            return Control.idle(observation.me().position());
        }
        var target = nearest.orElseThrow();
        Vec2 inward = target.position().subtract(observation.me().position());
        double distance = Math.max(1, inward.length());
        Vec2 radial = inward.scale(1 / distance);
        Vec2 tangent = new Vec2(-radial.y(), radial.x());
        double approach = Math.clamp((distance - 260) / 200, -0.8, 1);
        Vec2 destination =
                observation
                        .me()
                        .position()
                        .add(radial.scale(approach * 120))
                        .add(tangent.scale(120));
        Vec2 centre = new Vec2(observation.map().width() / 2, observation.map().height() / 2);
        boolean retreat =
                observation.me().position().distance(centre) + observation.rules().tankRadius() + 50
                        > observation.state().safeRadius();
        destination = retreat ? centre : BotGeometry.insideZone(observation, destination);
        if (!retreat
                && !BotGeometry.clearPath(
                        observation.map(),
                        observation.me().position(),
                        target.position(),
                        observation.rules().bulletRadius())) {
            destination = target.position();
        }
        destination = BotGeometry.navigateToward(observation, destination);
        Vec2 aim = BotGeometry.predictedAim(observation, target);
        return new Control(
                BotGeometry.forwardThrottle(observation, destination),
                BotGeometry.turnToward(observation, destination),
                aim,
                BotGeometry.clearPath(
                        observation.map(),
                        observation.me().position(),
                        aim,
                        observation.rules().bulletRadius()),
                observation.me().shieldCooldownTicks() == 0
                        && observation.state().projectiles().stream()
                                .anyMatch(projectile -> threatens(observation, projectile)));
    }

    private static boolean threatens(Observation observation, Projectile projectile) {
        if (projectile.owner() == observation.self()) {
            return false;
        }
        Vec2 towardTank = observation.me().position().subtract(projectile.position());
        Vec2 velocity = projectile.velocity();
        double speedSquared = velocity.x() * velocity.x() + velocity.y() * velocity.y();
        if (speedSquared == 0) {
            return false;
        }
        double seconds =
                (towardTank.x() * velocity.x() + towardTank.y() * velocity.y()) / speedSquared;
        if (seconds <= 0 || seconds > 0.5) {
            return false;
        }
        double missDistance = towardTank.subtract(velocity.scale(seconds)).length();
        return missDistance
                <= observation.rules().tankRadius() + observation.rules().bulletRadius();
    }
}
