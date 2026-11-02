package edu.arena.core.bot;

import edu.arena.core.model.Control;
import edu.arena.core.model.Vec2;

/** Pursues the nearest living opponent, leads shots, and retreats from the shrinking zone. */
public final class HunterBot implements Bot {
    @Override
    public Control decide(Observation observation) {
        var nearest =
                BotGeometry.nearestVisibleOpponent(observation).or(observation::nearestOpponent);
        if (observation.me().health() == 0 || nearest.isEmpty()) {
            return Control.idle(observation.me().position());
        }
        var target = nearest.orElseThrow();
        var centre = new Vec2(observation.map().width() / 2, observation.map().height() / 2);
        boolean retreat =
                observation.me().position().distance(centre) + observation.rules().tankRadius() + 50
                        > observation.state().safeRadius();
        var destination = retreat ? centre : BotGeometry.insideZone(observation, target.position());
        destination = BotGeometry.navigateToward(observation, destination);
        double distance = target.position().distance(observation.me().position());
        double throttle =
                retreat || distance > 100
                        ? BotGeometry.forwardThrottle(observation, destination)
                        : 0;
        var aim = BotGeometry.interceptAim(observation, target);
        var direction = aim.subtract(observation.me().position());
        double gunError =
                Math.abs(
                        BotGeometry.angleDifference(
                                Math.atan2(direction.y(), direction.x()),
                                observation.me().gunHeading()));
        boolean fire =
                observation.state().tick() > 0
                        && BotGeometry.clearPath(
                                observation.map(),
                                observation.me().position(),
                                aim,
                                observation.rules().bulletRadius())
                        && gunError
                                <= observation.rules().turretSpeed()
                                                / observation.rules().tickRate()
                                        + 0.03;
        return new Control(throttle, BotGeometry.turnToward(observation, destination), aim, fire);
    }
}
