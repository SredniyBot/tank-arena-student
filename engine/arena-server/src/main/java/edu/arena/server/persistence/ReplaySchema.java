package edu.arena.server.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Read-only adaptation of older archive versions; current required and unknown fields stay strict.
 */
final class ReplaySchema {
    private ReplaySchema() {}

    static void index(ObjectNode root) {
        if (!root.path("version").isIntegralNumber()) {
            return;
        }
        int version = root.path("version").intValue();
        if (version >= 2 && version <= 4 && root.path("match") instanceof ObjectNode matchObject) {
            if (!matchObject.has("controlTiming")) {
                matchObject.putNull("controlTiming");
            }
            delivery(matchObject.path("state"));
        }
        if ((version == 2 || version == 3)
                && root.path("match") instanceof ObjectNode matchObject
                && !matchObject.has("live")) {
            matchObject.putNull("live");
        }
        if (version != 2) {
            return;
        }
        var match = root.path("match");
        if (match.path("rules") instanceof ObjectNode rules
                && rules.path("tickRate").canConvertToInt()) {
            int rate = rules.path("tickRate").intValue();
            if (rate > 0 && rate <= Integer.MAX_VALUE / 8) {
                add(rules, "shieldDurationTicks", rate * 2);
                add(rules, "shieldCooldownTicks", rate * 8);
            }
        }
        state(match.path("state"));
    }

    static void chunk(ObjectNode root, int version) {
        if (version >= 2 && version <= 4) {
            root.path("frames").forEach(ReplaySchema::delivery);
        }
        if (version != 2) {
            return;
        }
        root.path("frames").forEach(ReplaySchema::state);
        root.path("turns")
                .forEach(
                        turn ->
                                turn.path("controls")
                                        .forEach(
                                                control -> {
                                                    if (control instanceof ObjectNode object
                                                            && !object.has("shield")) {
                                                        object.put("shield", false);
                                                    }
                                                }));
    }

    private static void delivery(JsonNode state) {
        if (state instanceof ObjectNode object && !object.has("delivery")) {
            object.putNull("delivery");
        }
    }

    private static void state(JsonNode state) {
        state.path("tanks")
                .forEach(
                        tank -> {
                            if (tank instanceof ObjectNode object) {
                                add(object, "shieldTicks", 0);
                                add(object, "shieldCooldownTicks", 0);
                            }
                        });
        state.path("eliminations")
                .forEach(
                        elimination -> {
                            if (elimination instanceof ObjectNode object) {
                                add(object, "actorSlot", -1);
                            }
                        });
    }

    private static void add(ObjectNode object, String name, int value) {
        if (!object.has(name)) {
            object.put(name, value);
        }
    }
}
