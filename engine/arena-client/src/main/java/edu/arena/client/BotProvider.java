package edu.arena.client;

import edu.arena.core.bot.Bot;

/**
 * Register a trusted local implementation through META-INF/services/edu.arena.client.BotProvider.
 */
public interface BotProvider {
    /** Local CLI strategy key, such as my-bot; independent of the player's display name. */
    String name();

    /**
     * Must return a non-null fresh instance for each finite match and each continuous-arena life.
     * Mutable strategy components must also be new instances; the SDK owns networking and respawn.
     */
    Bot create();
}
