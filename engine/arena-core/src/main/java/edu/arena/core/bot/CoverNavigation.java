package edu.arena.core.bot;

import static edu.arena.core.engine.ArenaGeometry.clearPath;

import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Vec2;
import edu.arena.core.model.Wall;
import java.util.ArrayList;
import java.util.Arrays;

/** Small visibility-graph route finder for examples, without bot state or shared mutable caches. */
final class CoverNavigation {
    private CoverNavigation() {}

    static Vec2 waypoint(Observation observation, Vec2 desired) {
        ArenaMap map = observation.map();
        Vec2 start = observation.me().position();
        double radius = observation.rules().tankRadius();
        double margin = radius + 12;
        Vec2 destination =
                new Vec2(
                        Math.clamp(desired.x(), margin, map.width() - margin),
                        Math.clamp(desired.y(), margin, map.height() - margin));
        if (clearPath(map, start, destination, radius)) {
            return destination;
        }
        var nodes = new ArrayList<Vec2>();
        nodes.add(start);
        nodes.add(destination);
        for (Wall wall : map.walls()) {
            for (double x : new double[] {wall.x() - margin, wall.x() + wall.width() + margin}) {
                for (double y :
                        new double[] {wall.y() - margin, wall.y() + wall.height() + margin}) {
                    var point = new Vec2(x, y);
                    if (clearPath(map, point, point, radius)) {
                        nodes.add(point);
                    }
                }
            }
        }
        double[] distance = new double[nodes.size()];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        int[] previous = new int[nodes.size()];
        Arrays.fill(previous, -1);
        boolean[] visited = new boolean[nodes.size()];
        distance[0] = 0;
        for (int iteration = 0; iteration < nodes.size(); iteration++) {
            int from = -1;
            for (int node = 0; node < nodes.size(); node++) {
                if (!visited[node]
                        && Double.isFinite(distance[node])
                        && (from < 0 || distance[node] < distance[from])) {
                    from = node;
                }
            }
            if (from < 0 || from == 1) {
                break;
            }
            visited[from] = true;
            for (int to = 0; to < nodes.size(); to++) {
                if (visited[to] || !clearPath(map, nodes.get(from), nodes.get(to), radius)) {
                    continue;
                }
                double candidate = distance[from] + nodes.get(from).distance(nodes.get(to));
                if (candidate < distance[to]) {
                    distance[to] = candidate;
                    previous[to] = from;
                }
            }
        }
        if (previous[1] < 0) {
            return start;
        }
        int next = 1;
        while (previous[next] > 0) {
            next = previous[next];
        }
        return nodes.get(next);
    }
}
