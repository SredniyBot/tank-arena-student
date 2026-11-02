package edu.arena.client;

import edu.arena.core.bot.Bot;
import edu.arena.core.bot.HunterBot;
import edu.arena.core.bot.SentryBot;
import edu.arena.core.bot.StrafeBot;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/** Built-in examples and explicit local providers. No classes are downloaded from the server. */
public final class BotCatalog {
    private BotCatalog() {}

    public static Supplier<Bot> factory(String name) {
        return switch (name) {
            case "sentry" -> SentryBot::new;
            case "hunter" -> HunterBot::new;
            case "strafe" -> StrafeBot::new;
            default -> provider(name);
        };
    }

    private static Supplier<Bot> provider(String name) {
        BotProvider selected = null;
        for (BotProvider provider : ServiceLoader.load(BotProvider.class)) {
            if (provider.name().equals(name)) {
                if (selected != null) {
                    throw new IllegalArgumentException("Duplicate bot provider name");
                }
                selected = provider;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException(
                    "Не найден бот «"
                            + name
                            + "». Соберите проект бота и добавьте его JAR в classpath. "
                            + "В студенческом комплекте выполните check, затем run.");
        }
        return selected::create;
    }
}
