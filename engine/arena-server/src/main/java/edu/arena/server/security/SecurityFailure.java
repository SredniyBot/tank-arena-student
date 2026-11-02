package edu.arena.server.security;

/** Expected security rejection with a safe public error message. */
public final class SecurityFailure extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;

    public SecurityFailure(int status, String code, String message) {
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
