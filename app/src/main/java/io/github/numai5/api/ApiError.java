package io.github.numai5.api;

public class ApiError extends Exception {
    public final boolean isTimeout;
    public final String raw;

    public ApiError(String message) {
        this(message, false, null);
    }

    public ApiError(String message, boolean isTimeout) {
        this(message, isTimeout, null);
    }

    public ApiError(String message, boolean isTimeout, String raw) {
        super(message);
        this.isTimeout = isTimeout;
        this.raw = raw;
    }
}
