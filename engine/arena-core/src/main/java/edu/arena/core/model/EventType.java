package edu.arena.core.model;

/** Visible events emitted during a single completed simulation tick. */
public enum EventType {
    SHOT,
    SUPPLY_HIT,
    HIT,
    WALL,
    DESTROYED,
    ZONE,
    SHIELD_ACTIVATED,
    SHIELD_BLOCK,
    ALLY_BLOCK
}
