package edu.arena.core.engine;

import edu.arena.core.model.Vec2;
import edu.arena.core.model.Wall;

/**
 * Sweeps return the first contact fraction in [0,1], or positive infinity when there is no contact.
 */
final class CollisionMath {
    private CollisionMath() {}

    static double circle(Vec2 relativeStart, Vec2 relativeDelta, double radius) {
        double c = dot(relativeStart, relativeStart) - radius * radius;
        double a = dot(relativeDelta, relativeDelta);
        double b = dot(relativeStart, relativeDelta);
        if (c <= 0) {
            return b < 0 || c < -1e-8 ? 0 : Double.POSITIVE_INFINITY;
        }
        if (a == 0 || b >= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double discriminant = b * b - a * c;
        if (discriminant < 0) {
            return Double.POSITIVE_INFINITY;
        }
        double t = (-b - Math.sqrt(discriminant)) / a;
        return t >= 0 && t <= 1 ? t : Double.POSITIVE_INFINITY;
    }

    static double wall(Vec2 from, Vec2 delta, Wall wall, double radius) {
        // The Minkowski sum is two rectangles and four round corners, not a larger square.
        double best =
                rectangle(
                        from,
                        delta,
                        wall.x() - radius,
                        wall.y(),
                        wall.width() + 2 * radius,
                        wall.height());
        best =
                Math.min(
                        best,
                        rectangle(
                                from,
                                delta,
                                wall.x(),
                                wall.y() - radius,
                                wall.width(),
                                wall.height() + 2 * radius));
        for (double x : new double[] {wall.x(), wall.x() + wall.width()}) {
            for (double y : new double[] {wall.y(), wall.y() + wall.height()}) {
                best = Math.min(best, circle(from.subtract(new Vec2(x, y)), delta, radius));
            }
        }
        return best;
    }

    static double rectangle(Vec2 p, Vec2 d, double x, double y, double width, double height) {
        double enter = 0;
        double exit = 1;
        double[] starts = {p.x(), p.y()};
        double[] deltas = {d.x(), d.y()};
        double[] low = {x, y};
        double[] high = {x + width, y + height};
        for (int axis = 0; axis < 2; axis++) {
            if (Math.abs(deltas[axis]) < 1e-15) {
                if (starts[axis] < low[axis] || starts[axis] > high[axis]) {
                    return Double.POSITIVE_INFINITY;
                }
            } else {
                double first = (low[axis] - starts[axis]) / deltas[axis];
                double second = (high[axis] - starts[axis]) / deltas[axis];
                enter = Math.max(enter, Math.min(first, second));
                exit = Math.min(exit, Math.max(first, second));
                if (enter > exit) {
                    return Double.POSITIVE_INFINITY;
                }
            }
        }
        return enter;
    }

    static double boundary(Vec2 from, Vec2 delta, double width, double height, double radius) {
        if (from.x() < radius
                || from.x() > width - radius
                || from.y() < radius
                || from.y() > height - radius) {
            return 0;
        }
        double best = Double.POSITIVE_INFINITY;
        if (delta.x() > 0) {
            best = Math.min(best, (width - radius - from.x()) / delta.x());
        }
        if (delta.x() < 0) {
            best = Math.min(best, (radius - from.x()) / delta.x());
        }
        if (delta.y() > 0) {
            best = Math.min(best, (height - radius - from.y()) / delta.y());
        }
        if (delta.y() < 0) {
            best = Math.min(best, (radius - from.y()) / delta.y());
        }
        return best >= 0 && best <= 1 ? best : Double.POSITIVE_INFINITY;
    }

    static double distanceToWall(Vec2 point, Wall wall) {
        double x = Math.max(wall.x(), Math.min(point.x(), wall.x() + wall.width()));
        double y = Math.max(wall.y(), Math.min(point.y(), wall.y() + wall.height()));
        return point.distance(new Vec2(x, y));
    }

    private static double dot(Vec2 a, Vec2 b) {
        return a.x() * b.x() + a.y() * b.y();
    }
}
