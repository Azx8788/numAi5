package io.github.numai5.api;

/**
 * 一次请求失败。
 *
 * 除了给用户看的 message，还带上排障所需的结构化信息：
 * httpCode / errorCode / retryable / retryAfterSeconds。
 * 自动重试只看 retryable —— 额度不足、Key 无效这类不可恢复的错误绝不重试，
 * 否则用户会等上一分多钟才看到同一个错误。
 */
public class ApiError extends Exception {
    public final boolean isTimeout;
    public final String raw;
    /** HTTP 状态码；0 表示不是 HTTP 层错误（解析失败、本地异常等） */
    public final int httpCode;
    /** 上游错误码，如 insufficientquota / invalid_api_key / model_not_found */
    public final String errorCode;
    /** 是否值得自动重试（限频、服务端 5xx、网络超时） */
    public final boolean retryable;
    /** 服务端要求的重试等待秒数（Retry-After），0 表示未提供 */
    public final int retryAfterSeconds;

    public ApiError(String message) {
        this(message, false, null);
    }

    public ApiError(String message, boolean isTimeout) {
        this(message, isTimeout, null);
    }

    public ApiError(String message, boolean isTimeout, String raw) {
        this(message, isTimeout, raw, 0, null, false, 0);
    }

    public ApiError(String message, boolean isTimeout, String raw, int httpCode,
                    String errorCode, boolean retryable, int retryAfterSeconds) {
        super(message);
        this.isTimeout = isTimeout;
        this.raw = raw;
        this.httpCode = httpCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
