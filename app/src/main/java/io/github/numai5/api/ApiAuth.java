package io.github.numai5.api;

/**
 * API Key 清洗与校验。
 *
 * 背景：OkHttp 要求请求头只能是可见 ASCII，一点中文混进 Key 就会抛
 * `Unexpected char 0x72ec at 7 in Authorization value`——用户完全看不懂，
 * 也想不到是「粘贴时把说明文字带进来了」。
 *
 * 这里做两件事：
 * 1. sanitize：自动剔除无害杂质（首尾空白、换行、包裹引号、Bearer 前缀、零宽字符、
 *    全角空格、以及误粘进来的中文说明文字）。Key 本身是一长串随机字符，手动删更容易出错。
 * 2. validate：清洗后仍不可用时，给出能照着操作的中文提示，而不是把 OkHttp 的英文异常抛给用户。
 *
 * 纯 Java 实现（不依赖 Android），便于沙箱单元测试。
 */
public final class ApiAuth {

    private static final String BEARER = "Bearer ";

    private ApiAuth() {
    }

    /**
     * 清洗 Key：只保留可见 ASCII（0x21~0x7E），丢弃空白、换行、中文、全角标点、零宽字符等；
     * 去掉误带的 "Bearer " 前缀与首尾引号。
     *
     * @return 清洗结果；入参为 null 时返回空串
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            s = s.substring(BEARER.length()).trim();
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x21 && c <= 0x7E) {
                sb.append(c);
            }
            // 其余字符一律丢弃：空格、换行、制表符、全角空格、中文、零宽空格、BOM 等
        }
        // 去掉首尾可能残留的引号（粘贴 JSON 片段时常见）
        while (sb.length() > 0 && isQuote(sb.charAt(0))) sb.deleteCharAt(0);
        while (sb.length() > 0 && isQuote(sb.charAt(sb.length() - 1))) sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'';
    }

    /** 清洗前后是否发生了变化（用于提示用户「已自动清理」） */
    public static boolean wasModified(String raw) {
        return raw != null && !raw.equals(sanitize(raw));
    }

    /**
     * 校验 Key 是否可用于请求头。
     *
     * @return null 表示可用；否则返回面向用户的中文错误说明
     */
    public static String validate(String rawKey, boolean required) {
        String key = sanitize(rawKey);
        if (key.length() == 0) {
            return required ? "未填写 API Key，请到设置中填写该供应商的 Key" : null;
        }
        if (key.length() < 8) {
            return "API Key 过短（清洗后仅 " + key.length() + " 个字符），请检查是否粘贴完整";
        }
        return null;
    }

    /** 该字符串是否只含可见 ASCII（请求头/URL 的硬性要求） */
    public static boolean isHeaderSafe(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    /** URL 清洗：去掉空白与中文等非法字符（地址里混入中文同样会让 OkHttp 抛异常） */
    public static String sanitizeUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x20 && c <= 0x7E) sb.append(c);
        }
        return sb.toString();
    }
}
