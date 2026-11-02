package edu.arena.core.model;

/** Axis-aligned solid rectangle; x/y mark the upper-left corner, all dimensions use world units. */
public record Wall(double x, double y, double width, double height) {
    public Wall {
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(width)
                || !Double.isFinite(height)
                || x < 0
                || y < 0
                || width <= 0
                || height <= 0
                || !Double.isFinite(x + width)
                || !Double.isFinite(y + height)) {
            throw new IllegalArgumentException("Invalid wall rectangle");
        }
    }
}
