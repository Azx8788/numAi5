package io.github.numai5.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;

/**
 * JSON 解析辅助。
 *
 * 背景：{@code JsonParser.parseString} 使用严格模式，遇到不规范但可读的 JSON 会直接抛
 * "Use JsonReader.setLenient(true) to accept malformed JSON"。部分中转站确实会返回
 * 未加引号的键、单引号字符串、尾随逗号这类内容，宽松解析能尽量兼容。
 *
 * 另外提供「响应根本不是 JSON」的识别：服务端返回 HTML 错误页或纯文本时，
 * 若仍硬当 JSON 解析，用户只会看到上面那句 Gson 报错，无法定位问题。
 */
public final class JsonUtil {

    private static final int SNIPPET_LENGTH = 200;

    private JsonUtil() {
    }

    /** 宽松解析单个 JSON 值；内容确实不是 JSON 时抛 JsonSyntaxException */
    public static JsonElement parseLenient(String text) {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(true);
        try {
            return JsonParser.parseReader(reader);
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 响应体是否像一个 JSON 文档（跳过空白与 BOM 后是否以 { 或 [ 开头） */
    public static boolean looksLikeJson(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFEFF' || Character.isWhitespace(c)) continue;
            return c == '{' || c == '[';
        }
        return false;
    }

    /** 响应开头片段，用于错误提示：折叠空白、截断过长内容 */
    public static String snippet(String text) {
        if (text == null) return "";
        String s = text.trim().replaceAll("\\s+", " ");
        return s.length() <= SNIPPET_LENGTH ? s : s.substring(0, SNIPPET_LENGTH) + "…";
    }
}
