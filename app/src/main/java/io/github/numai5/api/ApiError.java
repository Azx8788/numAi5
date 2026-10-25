package io.github.numai5.api;

/**
 * 一次请求失败。
 *
 * 除了给用户看的 message，还带上排障所需的结构化信息：
 * httpCode / errorCode / retryable / retryAfterSeconds / kind。
 * 调度器只看 kind：限流 → 供应商冷却；额度耗尽 / 认证失败 → 暂停该供应商；
 * 本版本对任何错误都不自动重发（见 ProviderRateLimiter.Defaults.AUTO_RETRY）。
 */
public class ApiError extends Exception {
    public final boolean isTimeout;
    public final String raw;
    /** HTTP 状态码；0 表示不是 HTTP 层错误（解析失败、本地异常等） */
    public final int httpCode;
    /** 上游错误码，如 insufficientquota / invalid_api_key / model_not_found */
    public final String errorCode;
    /** 是否值得稍后再试（仅作提示；本版本不会自动重发） */
    public final boolean retryable;
    /** 服务端要求的重试等待秒数（Retry-After），0 表示未提供 */
    public final int retryAfterSeconds;
    /** 错误大类，决定调度器是否冷却/暂停该供应商 */
    public final ErrorMapper.Kind kind;

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
        this(message, isTimeout, raw, httpCode, errorCode, retryable, retryAfterSeconds,
                ErrorMapper.Kind.OTHER);
    }

    public ApiError(String message, boolean isTimeout, String raw, int httpCode,
                    String errorCode, boolean retryable, int retryAfterSeconds,
                    ErrorMapper.Kind kind) {
        super(message);
        this.isTimeout = isTimeout;
        this.raw = raw;
        this.httpCode = httpCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
        this.kind = kind == null ? ErrorMapper.Kind.OTHER : kind;
    }
}
