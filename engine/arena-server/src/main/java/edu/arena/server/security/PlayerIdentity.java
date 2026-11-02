package edu.arena.server.security;

/** Public identity; contains no credential material. */
public record PlayerIdentity(String id, String name, boolean revoked, boolean approved) {
    /** Trusted local identities and pre-approval tournament fixtures remain admitted. */
    public PlayerIdentity(String id, String name, boolean revoked) {
        this(id, name, revoked, true);
    }
}
