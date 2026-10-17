package io.github.numai5.api.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条流式 chunk 解码后的中性增量（借鉴 RikkaHub StreamChunk 思路）。
 * 各协议解码器把原始 chunk 翻译成这个结构，上层（ApiService）不再感知字段差异。
 */
public class ChunkDelta {

    /** 正文增量（可能为 null） */
    public String content;
    /** 思考过程增量（可能为 null） */
    public String reasoning;
    /** 工具调用增量（可能为空） */
    public final List<ToolCallDelta> toolCalls = new ArrayList<>();
    /** 终止原因（可能为 null） */
    public String finishReason;

    /** 工具调用增量 */
    public static class ToolCallDelta {
        public String id;
        public int index;
        public String name;
        public String arguments;
    }
}
