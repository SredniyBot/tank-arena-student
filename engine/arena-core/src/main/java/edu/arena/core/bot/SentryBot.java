package edu.arena.core.bot;

import edu.arena.core.model.Control;

/** First lesson: select the nearest living opponent, aim and fire while standing still. */
public final class SentryBot implements Bot {
    @Override
    public Control decide(Observation observation) {
        if (observation.me().health() == 0) {
            return Control.idle(observation.me().position());
        }
        return BotGeometry.nearestVisibleOpponent(observation)
                .map(target -> new Control(0, 0, target.position(), true))
                .orElseGet(() -> Control.idle(observation.me().position()));
    }
}
