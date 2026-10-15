package io.github.numai5.provider;

/**
 * 单个模型的定义。承载审查报告修订的全部兼容开关：
 *  - supportsTools          (B5) R1 等不支持 function calling → false
 *  - keepReasoningInHistory (A1) DeepSeek V3.2+/V4 thinking → true，其余 false
 *  - needsMaxCompletionTokens (A4) o系/gpt-5 → true（用 max_completion_tokens）
 *  - isReasoningOnly        (A4) 不接受 temperature
 *  - effortDialect          (A3/B1) "standard" 或 "newapi"（方言模式发 max）
 */
public class ModelDef {
    public String id;
    public String displayName;
    public String providerId;
    public boolean supportsThinking;
    public boolean supportsVision;
    public boolean supportsTools = true;
    public boolean keepReasoningInHistory = false;
    public boolean needsMaxCompletionTokens = false;
    public boolean isReasoningOnly = false;
    public String effortDialect = "standard"; // standard | newapi

    public ModelDef() {
    }

    public ModelDef(String id, String displayName, String providerId,
                    boolean supportsThinking, boolean supportsVision) {
        this.id = id;
        this.displayName = displayName;
        this.providerId = providerId;
        this.supportsThinking = supportsThinking;
        this.supportsVision = supportsVision;
    }

    /** Builder 风格设置兼容开关 */
    public ModelDef tools(boolean v) { this.supportsTools = v; return this; }
    public ModelDef keepReasoning(boolean v) { this.keepReasoningInHistory = v; return this; }
    public ModelDef maxCompletion(boolean v) { this.needsMaxCompletionTokens = v; return this; }
    public ModelDef reasoningOnly(boolean v) { this.isReasoningOnly = v; return this; }
    public ModelDef dialect(String d) { this.effortDialect = d; return this; }

    @Override
    public String toString() {
        return displayName + " (" + id + ")";
    }
}
