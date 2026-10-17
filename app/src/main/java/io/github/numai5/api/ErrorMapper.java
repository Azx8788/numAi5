package io.github.numai5.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * 把各家上游五花八门的错误响应，翻译成一句用户能看懂、并且知道下一步该做什么的话。
 *
 * 背景：此前直接把 `429 {"error":{"code":"insufficientquota",...}}` 原样塞进气泡，
 * 用户只能看到一串 JSON，既不知道是自己账号的问题、还是软件坏了。
 *
 * 纯 Java 实现（不依赖 Android），便于沙箱单元测试。
 */
public final class ErrorMapper {

    /** 单条错误信息最大长度，避免把整段 HTML 塞进气泡 */
    private static final int MAX_MESSAGE = 300;

    private ErrorMapper() {
    }

    /** 把 HTTP 错误响应翻译成 ApiError（含是否可重试的判定） */
    public static ApiError fromHttp(int httpCode, String body) {
        String code = extractCode(body);
        String detail = extractMessage(body);
        boolean retryable = isRetryable(httpCode, code, detail, body);
        // isTimeout 只表示「请求超时」，不能拿 5xx 顶替：UI 会据此提示「请检查网络」，
        // 而上游 500 是服务端故障，两者含义不同。
        return new ApiError(friendlyText(httpCode, code, detail, body), false,
                body, httpCode, code, retryable, 0);
    }

    /** 网络层错误（超时、DNS、连接重置等）：默认视为可重试 */
    public static ApiError fromNetwork(String detail, boolean timeout) {
        String text = timeout
                ? "请求超时：网络或中转站响应过慢，请稍后重试"
                : "网络错误：" + safe(detail) + "，请检查网络后重试";
        return new ApiError(text, timeout, null, 0, null, true, 0);
    }

    /** 是否值得自动重试：限频、服务端故障、超时可重试；额度/Key/模型不存在不可重试 */
    public static boolean isRetryable(int httpCode, String code, String detail, String body) {
        String all = lower(join(code, detail, body));
        if (isQuota(all) || isAuth(all) || isModelMissing(all)) return false;
        if (httpCode == 429) return true;
        if (httpCode == 408 || httpCode == 425) return true;
        if (httpCode >= 500 && httpCode != 501 && httpCode != 505) return true;
        return all.contains("rate limit") || all.contains("too many requests")
                || all.contains("overloaded") || all.contains("temporarily");
    }

    // ===== 文案 =====

    private static String friendlyText(int httpCode, String code, String detail, String body) {
        String all = lower(join(code, detail, body));

        if (isQuota(all)) {
            return withDetail("账号额度不足：该供应商账号的模型额度已用尽，请充值、改用其它模型，或换一个供应商", httpCode, code, detail);
        }
        if (isAuth(all)) {
            return withDetail("API Key 无效或已失效：请到设置中重新填写该供应商的 Key", httpCode, code, detail);
        }
        if (isModelMissing(all)) {
            return withDetail("模型不可用：该站没有此模型或当前无可用渠道，请重新拉取模型并另选一个", httpCode, code, detail);
        }
        if (isContextTooLong(all)) {
            return withDetail("上下文过长：请新建会话或删除部分历史消息后重试", httpCode, code, detail);
        }
        if (isContentFilter(all)) {
            return withDetail("内容被上游安全策略拦截，请调整表述后重试", httpCode, code, detail);
        }
        if (httpCode == 429) {
            return withDetail("请求过于频繁：已被上游限流，稍后会自动重试", httpCode, code, detail);
        }
        if (httpCode == 402) {
            return withDetail("需要付费：该供应商账户余额不足", httpCode, code, detail);
        }
        if (httpCode >= 500) {
            return withDetail("上游服务异常（HTTP " + httpCode + "），稍后会自动重试", httpCode, code, detail);
        }
        if (httpCode > 0) {
            return withDetail("请求失败（HTTP " + httpCode + "）", httpCode, code, detail);
        }
        return withDetail("请求失败", httpCode, code, detail);
    }

    /** 拼接「主提示 + 原始信息」，原始信息截断，便于用户反馈时对照 */
    private static String withDetail(String main, int httpCode, String code, String detail) {
        StringBuilder sb = new StringBuilder(main);
        if (code != null && code.length() > 0) {
            sb.append("\n错误码：").append(code);
        }
        if (detail != null && detail.length() > 0 && !detail.equalsIgnoreCase(code)) {
            sb.append("\n原始信息：").append(safe(detail));
        }
        return sb.toString();
    }

    private static boolean isQuota(String all) {
        return all.contains("insufficientquota") || all.contains("insufficient_quota")
                || all.contains("quota") && (all.contains("exceed") || all.contains("exhaust") || all.contains("insufficient"))
                || all.contains("exceeded your current quota")
                || all.contains("余额不足") || all.contains("额度") && all.contains("用尽")
                || all.contains("budget pool quota")
                || all.contains("billing");
    }

    private static boolean isAuth(String all) {
        return all.contains("invalid_api_key") || all.contains("invalid api key")
                || all.contains("incorrect api key") || all.contains("unauthorized")
                || all.contains("unauthenticated") || all.contains("authentication")
                || all.contains("api key not valid") || all.contains("invalid key");
    }

    private static boolean isModelMissing(String all) {
        return all.contains("model_not_found") || all.contains("model not found")
                || all.contains("does not exist") || all.contains("no such model")
                || all.contains("无可用渠道") || all.contains("no available channel")
                || all.contains("model is not supported");
    }

    private static boolean isContextTooLong(String all) {
        return all.contains("context_length_exceeded") || all.contains("maximum context length")
                || all.contains("context length") || all.contains("too many tokens")
                || all.contains("token limit");
    }

    private static boolean isContentFilter(String all) {
        return all.contains("content_filter") || all.contains("content policy")
                || all.contains("safety") || all.contains("blocked");
    }

    // ===== 解析 =====

    /** 提取可读消息：error.message / message / error（字符串）/ detail / 原文 */
    public static String extractMessage(String body) {
        if (body == null || body.length() == 0) return "";
        try {
            JsonElement root = JsonUtil.parseLenient(body);
            if (root != null && root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                String nested = nestedMessage(obj.get("error"));
                if (nested != null) return nested;
                String msg = primitive(obj.get("message"));
                if (msg != null) return msg;
                String detail = primitive(obj.get("detail"));
                if (detail != null) return detail;
                String errString = primitive(obj.get("error"));
                if (errString != null) return errString;
            }
        } catch (Exception ignored) {
        }
        return JsonUtil.snippet(body);
    }

    /** 提取错误码：error.code / code / error.type / type */
    public static String extractCode(String body) {
        if (body == null || body.length() == 0) return null;
        try {
            JsonElement root = JsonUtil.parseLenient(body);
            if (root != null && root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                JsonElement err = obj.get("error");
                if (err != null && err.isJsonObject()) {
                    JsonObject errObj = err.getAsJsonObject();
                    String c = primitive(errObj.get("code"));
                    if (c != null) return c;
                    String t = primitive(errObj.get("type"));
                    if (t != null) return t;
                }
                String c2 = primitive(obj.get("code"));
                if (c2 != null) return c2;
                String t2 = primitive(obj.get("type"));
                if (t2 != null) return t2;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String nestedMessage(JsonElement err) {
        if (err == null || err.isJsonNull()) return null;
        if (err.isJsonObject()) {
            JsonObject obj = err.getAsJsonObject();
            String msg = primitive(obj.get("message"));
            if (msg != null) return msg;
            String detail = primitive(obj.get("detail"));
            if (detail != null) return detail;
        }
        return null;
    }

    private static String primitive(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        try {
            String s = element.getAsString();
            return s == null || s.trim().length() == 0 ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String safe(String detail) {
        if (detail == null) return "";
        String s = detail.trim().replaceAll("\\s+", " ");
        return s.length() <= MAX_MESSAGE ? s : s.substring(0, MAX_MESSAGE) + "…";
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null) sb.append(p).append(' ');
        }
        return sb.toString();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
