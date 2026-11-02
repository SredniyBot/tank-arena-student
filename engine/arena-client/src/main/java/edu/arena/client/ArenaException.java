package edu.arena.client;

import java.io.IOException;

/** Safe server error; never includes the request, token or raw response. */
public final class ArenaException extends IOException {
    @java.io.Serial private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;
    private final int retryAfterSeconds;

    public ArenaException(int status, String code, String message, int retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean transientFailure() {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }
}
