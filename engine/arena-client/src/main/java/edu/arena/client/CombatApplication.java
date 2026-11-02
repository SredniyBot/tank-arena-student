package edu.arena.client;

import edu.arena.core.combat.CombatBot;
import edu.arena.core.combat.CombatObservation;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/** IDE entry point using the existing authenticated personal-observation transport. */
public final class CombatApplication {
    private CombatApplication() {}

    public static void run(Supplier<CombatBot> bots) throws IOException, InterruptedException {
        Objects.requireNonNull(bots, "bots");
        ReconApplication.run(
                () -> {
                    CombatBot bot = Objects.requireNonNull(bots.get(), "bot");
                    return view -> bot.decide(CombatObservation.from(view));
                });
    }
}
