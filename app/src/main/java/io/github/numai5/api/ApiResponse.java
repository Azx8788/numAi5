package io.github.numai5.api;

public class ApiResponse {
    public final int statusCode;
    public final String body;
    public final String errorBody;
    public final boolean successful;

    public ApiResponse(int statusCode, String body, String errorBody) {
        this.statusCode = statusCode;
        this.body = body;
        this.errorBody = errorBody;
        this.successful = statusCode >= 200 && statusCode < 300;
    }
}
