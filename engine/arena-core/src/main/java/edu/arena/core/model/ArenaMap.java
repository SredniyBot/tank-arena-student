package edu.arena.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable rectangular arena, measured in world units, with 2 to 64 spawn points. */
public record ArenaMap(
        String id, double width, double height, List<Wall> walls, List<Spawn> spawns) {
    public ArenaMap {
        if (id == null
                || id.isBlank()
                || !Double.isFinite(width)
                || !Double.isFinite(height)
                || width <= 0
                || height <= 0) {
            throw new IllegalArgumentException("Invalid map metadata");
        }
        walls = List.copyOf(Objects.requireNonNull(walls, "walls"));
        spawns = List.copyOf(Objects.requireNonNull(spawns, "spawns"));
        if (spawns.size() < 2 || spawns.size() > 64) {
            throw new IllegalArgumentException("An arena requires 2 to 64 spawns");
        }
        for (var wall : walls) {
            if (wall.x() + wall.width() > width || wall.y() + wall.height() > height) {
                throw new IllegalArgumentException("Wall outside map");
            }
        }
        for (var spawn : spawns) {
            if (spawn.position().x() < 0
                    || spawn.position().x() > width
                    || spawn.position().y() < 0
                    || spawn.position().y() > height) {
                throw new IllegalArgumentException("Spawn outside map");
            }
        }
    }

    /** Equal-distance starts facing the centre; the host assigns identities to these positions. */
    public static ArenaMap commons(int participants) {
        if (participants < 2 || participants > 64) {
            throw new IllegalArgumentException("Participants must be between 2 and 64");
        }
        var starts = new ArrayList<Spawn>(participants);
        for (int slot = 0; slot < participants; slot++) {
            double angle = -Math.PI / 2 + 2 * Math.PI * slot / participants;
            starts.add(
                    new Spawn(
                            new Vec2(640 + 540 * Math.cos(angle), 640 + 540 * Math.sin(angle)),
                            Math.IEEEremainder(angle + Math.PI, 2 * Math.PI)));
        }
        return new ArenaMap("commons", 1280, 1280, List.of(), starts);
    }

    /** Fourfold-symmetric cover with wide connected corridors; both tanks and bullets hit walls. */
    public static ArenaMap coveredCommons(int capacity) {
        return new ArenaMap(
                "covered-commons",
                1280,
                1280,
                List.of(
                        new Wall(400, 400, 120, 120), new Wall(760, 400, 120, 120),
                        new Wall(400, 760, 120, 120), new Wall(760, 760, 120, 120),
                        new Wall(560, 260, 160, 60), new Wall(560, 960, 160, 60),
                        new Wall(260, 560, 60, 160), new Wall(960, 560, 60, 160)),
                commons(capacity).spawns());
    }
}
