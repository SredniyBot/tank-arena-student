package student;

import edu.arena.client.CombatApplication;

/** Run from the IDE with Java 21. Registration prompts appear in the console. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        CombatApplication.run(MyBot::new);
    }
}
