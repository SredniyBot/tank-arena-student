package student;

import edu.arena.client.BotApplication;
import java.io.IOException;

/** Run this class from the IDE. Connection details are entered in its console. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        BotApplication.run(MyBot::new);
    }
}
