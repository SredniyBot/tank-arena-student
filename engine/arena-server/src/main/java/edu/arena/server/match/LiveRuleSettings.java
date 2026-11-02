package edu.arena.server.match;

import edu.arena.core.model.GameRules;
import edu.arena.protocol.Messages.GameConditions;
import edu.arena.protocol.Messages.RuleLimit;
import edu.arena.server.config.ServerConfig;
import java.util.Map;

/** Validation and tick conversion for the small, explicitly permitted set of live game changes. */
final class LiveRuleSettings {
    private static final Map<String, RuleLimit> LIMITS =
            Map.of(
                    "moveSpeed", new RuleLimit(30, 300),
                    "turnSpeed", new RuleLimit(0.5, 6),
                    "turretSpeed", new RuleLimit(0.5, 12),
                    "bulletSpeed", new RuleLimit(120, 900),
                    "reloadMillis", new RuleLimit(100, 10000),
                    "shieldDurationMillis", new RuleLimit(100, 10000),
                    "shieldCooldownMillis", new RuleLimit(100, 60000),
                    "respawnMillis", new RuleLimit(100, 30000));

    private LiveRuleSettings() {}

    static Map<String, RuleLimit> limits() {
        return LIMITS;
    }

    static GameConditions defaults(ServerConfig config) {
        var rules = config.game().rules();
        int rate = rules.tickRate();
        return new GameConditions(
                rules.moveSpeed(),
                rules.turnSpeed(),
                rules.turretSpeed(),
                rules.bulletSpeed(),
                millis(rules.reloadTicks(), rate),
                millis(rules.shieldDurationTicks(), rate),
                millis(rules.shieldCooldownTicks(), rate),
                millis(config.live().respawnTicks(rate), rate));
    }

    static GameConditions normalize(GameConditions values, int rate) {
        if (values == null) {
            throw invalid("Укажите полный набор игровых условий.");
        }
        check("moveSpeed", values.moveSpeed());
        check("turnSpeed", values.turnSpeed());
        check("turretSpeed", values.turretSpeed());
        check("bulletSpeed", values.bulletSpeed());
        check("reloadMillis", values.reloadMillis());
        check("shieldDurationMillis", values.shieldDurationMillis());
        check("shieldCooldownMillis", values.shieldCooldownMillis());
        check("respawnMillis", values.respawnMillis());
        if (values.shieldCooldownMillis() < values.shieldDurationMillis()) {
            throw invalid("Перезарядка щита не может быть короче его действия.");
        }
        return new GameConditions(
                values.moveSpeed(),
                values.turnSpeed(),
                values.turretSpeed(),
                values.bulletSpeed(),
                rounded(values.reloadMillis(), rate),
                rounded(values.shieldDurationMillis(), rate),
                rounded(values.shieldCooldownMillis(), rate),
                rounded(values.respawnMillis(), rate));
    }

    static GameRules rules(GameRules base, GameConditions values) {
        int rate = base.tickRate();
        return new GameRules(
                rate,
                base.maxTicks(),
                base.hitPoints(),
                values.moveSpeed(),
                values.turnSpeed(),
                values.turretSpeed(),
                values.bulletSpeed(),
                ticks(values.reloadMillis(), rate),
                base.tankRadius(),
                base.bulletRadius(),
                base.zoneStartTick(),
                base.zoneEndTick(),
                base.zoneDamageIntervalTicks(),
                ticks(values.shieldDurationMillis(), rate),
                ticks(values.shieldCooldownMillis(), rate));
    }

    static int ticks(double millis, int rate) {
        // The tolerance preserves an already-normalized fractional millisecond value.
        return (int) Math.ceil(millis * rate / 1000.0 - 1e-9);
    }

    private static double millis(int ticks, int rate) {
        return ticks * 1000.0 / rate;
    }

    private static double rounded(double value, int rate) {
        return millis(ticks(value, rate), rate);
    }

    private static void check(String field, double value) {
        var range = LIMITS.get(field);
        if (!Double.isFinite(value) || value < range.min() || value > range.max()) {
            throw invalid("Параметр " + field + " вне допустимого диапазона.");
        }
    }

    private static ApplicationFailure invalid(String message) {
        return new ApplicationFailure(400, "INVALID_RULES", message);
    }
}
