package edu.arena.server.security;

/** The token is returned once and must never be logged. */
public record Enrollment(String playerId, String name, String token) {
    @Override
    public String toString() {
        return "Enrollment[playerId=" + playerId + ", name=" + name + ", token=<redacted>]";
    }
}
