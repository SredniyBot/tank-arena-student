package edu.arena.server.match;

/** Expected application rejection, safe to translate at the HTTP boundary. */
public final class ApplicationFailure extends RuntimeException {
    private final int status;
    private final String code;

    public ApplicationFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
