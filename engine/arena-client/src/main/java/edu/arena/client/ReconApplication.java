package edu.arena.client;

import edu.arena.core.recon.ReconBot;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * IDE entry point for personal-vision team play; registration is shared with the ordinary client.
 */
public final class ReconApplication {
    private ReconApplication() {}

    public static void run(Supplier<ReconBot> bots) throws IOException, InterruptedException {
        Objects.requireNonNull(bots, "bots");
        BotApplication.connect(
                (client, progress) -> new ReconBotRunner(client, bots).run(progress),
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                Path.of(System.getProperty("user.home"), ".tank-arena", "players"),
                true);
    }
}
