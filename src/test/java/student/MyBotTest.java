package student;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.arena.core.bot.Observation;
import edu.arena.core.engine.ArenaEngine;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.GameRules;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Contract checks accept any valid strategy; they do not require the bot to remain idle. */
@Timeout(5)
class MyBotTest {
    @ParameterizedTest
    @ValueSource(ints = {2, 12, 32, 64})
    void everyStartingSlotProducesAValidCommand(int participants) {
        var map = ArenaMap.coveredCommons(participants);
        var rules = GameRules.standard(20, 20);
        var state = new ArenaEngine(rules, map).initialState();

        for (int slot = 0; slot < participants; slot++) {
            var view = new Observation(slot, state, map, rules);
            assertValid(new MyBot().decide(view), view);
        }
    }

    @Test
    void havingNoLivingOpponentsDoesNotBreakTheDecision() {
        var map = ArenaMap.commons(2);
        var rules = GameRules.standard(20, 20);
        var engine = new ArenaEngine(rules, map);
        var state = engine.eliminate(engine.initialState(), Set.of(1), "FORFEIT");
        var view = new Observation(0, state, map, rules);

        assertTrue(view.opponents().isEmpty());
        assertValid(new MyBot().decide(view), view);
    }

    @Test
    void decisionsStayValidDuringPlayAndAfterRespawn() {
        var map = ArenaMap.coveredCommons(12);
        var rules = GameRules.standard(20, 20);
        var engine = new ArenaEngine(rules, map);
        var state = engine.initialState();
        var bot = new MyBot();

        for (int step = 0; step < 200; step++) {
            if (step == 100) {
                state = engine.respawn(engine.vacate(state, 0), 0, 30);
                bot = new MyBot();
                assertTrue(state.tanks().getFirst().health() > 0);
            }
            var view = new Observation(0, state, map, rules);
            Control control = bot.decide(view);
            assertValid(control, view);
            var commands = new ArrayList<Control>();
            commands.add(control);
            for (int slot = 1; slot < state.tanks().size(); slot++) {
                commands.add(Control.idle(state.tanks().get(slot).position()));
            }
            state = engine.stepContinuous(state, commands);
        }

        assertEquals(200, state.tick());
    }

    private static void assertValid(Control control, Observation view) {
        assertNotNull(control, "decide must return a command");
        assertAll(
                () ->
                        assertTrue(
                                Double.isFinite(control.throttle())
                                        && Math.abs(control.throttle()) <= 1,
                                "Throttle must be finite and between -1 and 1"),
                () ->
                        assertTrue(
                                Double.isFinite(control.turn()) && Math.abs(control.turn()) <= 1,
                                "Turn must be finite and between -1 and 1"),
                () ->
                        assertTrue(
                                Math.abs(control.aim().x()) <= view.map().width() * 4,
                                "Aim x must fit the server bounds"),
                () ->
                        assertTrue(
                                Math.abs(control.aim().y()) <= view.map().height() * 4,
                                "Aim y must fit the server bounds"));
    }
}
