package io.github.numai5.model;

import android.content.Context;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.github.numai5.ui.MarkdownParser;

/**
 * 消息数据模型（基于 numAi 改造）。
 * 关键改造：
 *  - inputImages 存文件名（非 base64），避免持久化膨胀（A6）
 *  - 工具调用字段名用标准 tool_call_id / tool_calls（A2）
 *  - 记录实际使用的 model/provider/thinkingLevel
 *  - 流式缓冲用 transient，不序列化
 */
public class Message {
    public String id;
    public String role;
    public String content = "";
    public String reasoningRaw;        // 对应 reasoning_content 原文
    public JsonArray toolCalls;        // OpenAI 标准 tool_calls 数组
    public String toolCallId;          // 标准字段名 tool_call_id（A2）
    public boolean isError;
    public long timestamp;
    public String model;               // assistant 实际使用的模型 id
    public String provider;            // 供应商显示名
    public String thinkingLevel;       // ThinkingLevel.name()
    public List<String> inputImages;   // 存本地图片文件名（A6）

    // 流式缓冲（不持久化）
    public transient StringBuilder thinkingBuffer;
    public transient StringBuilder contentBuffer;
    public transient StringBuilder matchingTagBuffer;
    public transient boolean isThinkingState;
    public transient List<StreamToolCall> streamingToolCalls;

    /**
     * 流式刷新信号（步骤2）：后台线程每次追加内容后自增；主线程读取该 volatile 字段判断是否有更新，
     * 并借助 volatile 的读写可见性保证 content/reasoningRaw 的安全读取。
     */
    public transient volatile int streamRev;

    // UI 缓存（不持久化）
    private transient String cachedRawContent = null;
    private transient CharSequence cachedParsedDisplayContent = null;
    private transient CharSequence cachedParsedThinkContent = null;
    private transient String cachedThinkingRaw = null;
    private transient String cachedDisplayRaw = null;

    public Message() {
    }

    public Message(Role role) {
        this.id = UUID.randomUUID().toString();
        this.role = role.name().toLowerCase();
        this.timestamp = System.currentTimeMillis();
    }

    public static Message userMessage() {
        return new Message(Role.USER);
    }

    public static Message assistantMessage() {
        return new Message(Role.ASSISTANT);
    }

    public static Message systemMessage() {
        return new Message(Role.SYSTEM);
    }

    public static Message toolMessage() {
        return new Message(Role.TOOL);
    }

    public Role getRoleEnum() {
        return Role.fromString(role);
    }

    public boolean isSent() {
        return getRoleEnum() == Role.USER;
    }

    public void setAsError() {
        this.isError = true;
    }

    public boolean hasReasoning() {
        return reasoningRaw != null && reasoningRaw.length() > 0;
    }

    public String getThinkingRaw() {
        ensureParsedStructure();
        return cachedThinkingRaw;
    }

    public String getDisplayRaw() {
        ensureParsedStructure();
        return cachedDisplayRaw;
    }

    public CharSequence getParsedDisplayContent(Context context) {
        ensureParsedStructure();
        if (cachedParsedDisplayContent == null) {
            cachedParsedDisplayContent = MarkdownParser.parse(context, cachedDisplayRaw, false);
        }
        return cachedParsedDisplayContent;
    }

    public CharSequence getParsedThinkContent(Context context) {
        ensureParsedStructure();
        if (cachedParsedThinkContent == null) {
            cachedParsedThinkContent = MarkdownParser.parse(context, cachedThinkingRaw, false);
        }
        return cachedParsedThinkContent;
    }

    public void invalidateCache() {
        cachedRawContent = null;
        cachedParsedDisplayContent = null;
        cachedParsedThinkContent = null;
        cachedThinkingRaw = null;
        cachedDisplayRaw = null;
    }

    /**
     * 从 content 中分离 thinking 与 display。
     * B6：若 reasoningRaw 已存在，不再从 content 解析 think 标签（防重复）。
     */
    private void ensureParsedStructure() {
        if (cachedRawContent != null && cachedRawContent.equals(content)) {
            return;
        }
        cachedRawContent = content;

        // 已有 reasoning_content 时，content 就是正文，不解析 think 标签
        if (reasoningRaw != null && reasoningRaw.length() > 0) {
            cachedThinkingRaw = reasoningRaw.trim();
            cachedDisplayRaw = content == null ? "" : content.trim();
            return;
        }

        if (content == null || content.length() == 0) {
            cachedThinkingRaw = "";
            cachedDisplayRaw = "";
            return;
        }

        String text = content;
        StringBuilder thinkSb = new StringBuilder();
        StringBuilder displaySb = new StringBuilder();
        int cursor = 0;

        // 兼容 <think>...</think> /  <think> ... </think>  标签
        while (cursor < text.length()) {
            int thinkStart = text.indexOf("<think>", cursor);
            if (thinkStart == -1) {
                displaySb.append(text.substring(cursor));
                break;
            }
            displaySb.append(text.substring(cursor, thinkStart));
            int thinkEnd = text.indexOf("</think>", thinkStart + 7);
            if (thinkEnd == -1) {
                thinkSb.append(text.substring(thinkStart + 7));
                cursor = text.length();
            } else {
                thinkSb.append(text.substring(thinkStart + 7, thinkEnd));
                cursor = thinkEnd + 8;
            }
        }

        cachedThinkingRaw = thinkSb.toString().trim();
        cachedDisplayRaw = displaySb.toString().trim();
    }

    /** 流式追加内容（步骤2：先写内容、后自增 streamRev，保证主线程能按序看到） */
    public void appendContent(String chunk) {
        if (chunk == null || chunk.length() == 0) return;
        if (content == null) content = "";
        content += chunk;
        invalidateCache();
        streamRev++;
    }

    public void appendReasoning(String chunk) {
        if (chunk == null || chunk.length() == 0) return;
        if (reasoningRaw == null) reasoningRaw = "";
        reasoningRaw += chunk;
        invalidateCache();
        streamRev++;
    }

    /** 工具调用流式聚合单元（不持久化） */
    public static class StreamToolCall {
        public String id;
        public int index;
        public String name;
        public StringBuilder arguments = new StringBuilder();
    }
}
