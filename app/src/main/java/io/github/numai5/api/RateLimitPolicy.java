package io.github.numai5.api;

import io.github.numai5.data.ProviderRateLimiter;
import io.github.numai5.provider.Provider;

/**
 * 错误 → 调度策略的唯一映射处。
 *
 * <ul>
 *   <li>限流：本次失败 + 该供应商进入冷却（Retry-After 有效时按服务端要求，否则本地默认冷却）。</li>
 *   <li>额度耗尽：暂停该供应商的自动调度，等用户充值/换供应商并保存配置后恢复。</li>
 *   <li>认证失败：暂停该供应商的自动调度，等用户修正 Key 并保存后恢复。</li>
 *   <li>其他：不冷却、不暂停，也不重试。</li>
 * </ul>
 *
 * 所有网络入口（聊天、模型列表、生图、魔搭轮询）都调这里，避免各处策略不一致。
 */
public final class RateLimitPolicy {

    private RateLimitPolicy() {
    }

    /** 把一次请求失败的分类结果落到供应商调度状态上 */
    public static void report(Provider provider, ApiError error) {
        if (provider == null || error == null) return;
        switch (error.kind) {
            case RATE_LIMIT:
                ProviderRateLimiter.onFailure(provider, ProviderRateLimiter.Failure.RATE_LIMIT,
                        error.retryAfterSeconds, error.getMessage());
                break;
            case QUOTA:
                ProviderRateLimiter.onFailure(provider, ProviderRateLimiter.Failure.QUOTA,
                        0, error.getMessage());
                break;
            case AUTH:
                ProviderRateLimiter.onFailure(provider, ProviderRateLimiter.Failure.AUTH,
                        0, error.getMessage());
                break;
            default:
                // 参数错误、权限错误、5xx、网络失败都不改变供应商调度状态
                break;
        }
    }

    /** 未获准发送 / 被取消时，把调度器的拒绝原因转成气泡里能看的错误 */
    public static ApiError denialError(ProviderRateLimiter.Denial denial) {
        if (denial == null) return new ApiError("请求未发送");
        int retryAfter = denial.retryAfterSeconds;
        ErrorMapper.Kind kind = ErrorMapper.Kind.OTHER;
        if (denial.reason == ProviderRateLimiter.Reason.COOLDOWN) kind = ErrorMapper.Kind.RATE_LIMIT;
        else if (denial.reason == ProviderRateLimiter.Reason.QUOTA_EXHAUSTED) kind = ErrorMapper.Kind.QUOTA;
        else if (denial.reason == ProviderRateLimiter.Reason.AUTH_FAILED) kind = ErrorMapper.Kind.AUTH;
        return new ApiError(denial.message, false, null, 0, null, false, retryAfter, kind);
    }
}
