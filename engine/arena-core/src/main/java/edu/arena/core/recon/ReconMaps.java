package edu.arena.core.recon;

import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Spawn;
import edu.arena.core.model.Vec2;
import edu.arena.core.model.Wall;
import java.util.ArrayList;
import java.util.List;

/** Rotationally symmetric bases and several routes around central cover. */
public final class ReconMaps {
    private ReconMaps() {}

    /** Original combat map, without depots, cargo or control zones. */
    public static ArenaMap combat(int participants) {
        var original = ArenaMap.coveredCommons(participants);
        return new ArenaMap(
                "independent-combat",
                original.width(),
                original.height(),
                original.walls(),
                original.spawns());
    }

    public static ArenaMap crossroads(int teams, int teamSize) {
        return new ArenaMap(
                "recon-crossroads",
                1280,
                1280,
                List.of(
                        new Wall(400, 400, 120, 120), new Wall(760, 400, 120, 120),
                        new Wall(400, 760, 120, 120), new Wall(760, 760, 120, 120),
                        new Wall(560, 260, 160, 60), new Wall(560, 960, 160, 60),
                        new Wall(260, 560, 60, 160), new Wall(960, 560, 60, 160)),
                spawns(teams, teamSize));
    }

    /** An open control map: useful to measure whether information matters without occlusion. */
    public static ArenaMap open(int teams, int teamSize) {
        return new ArenaMap("recon-open", 1280, 1280, List.of(), spawns(teams, teamSize));
    }

    /** Symmetric control field with direct starting routes and four solid obstacles. */
    public static ArenaMap controlPoints(int teams, int teamSize) {
        return new ArenaMap(
                "recon-control",
                1280,
                1280,
                List.of(
                        new Wall(600, 230, 80, 40),
                        new Wall(1010, 600, 40, 80),
                        new Wall(600, 1010, 80, 40),
                        new Wall(230, 600, 40, 80)),
                spawns(teams, teamSize));
    }

    private static List<Spawn> spawns(int teams, int teamSize) {
        var mode = new ReconRules(teams, teamSize, 340, 60, 40, 100);
        var corners =
                List.of(
                        new Vec2(140, 140),
                        new Vec2(1140, 140),
                        new Vec2(1140, 1140),
                        new Vec2(140, 1140));
        var result = new ArrayList<Spawn>(mode.capacity());
        for (int team = 0; team < teams; team++) {
            Vec2 base = corners.get(teams == 2 ? 2 * team : team);
            double heading = Math.atan2(640 - base.y(), 640 - base.x());
            Vec2 tangent = new Vec2(-Math.sin(heading), Math.cos(heading));
            for (int member = 0; member < teamSize; member++) {
                double offset = (member - (teamSize - 1) / 2.0) * 54;
                result.add(new Spawn(base.add(tangent.scale(offset)), heading));
            }
        }
        return List.copyOf(result);
    }
}
