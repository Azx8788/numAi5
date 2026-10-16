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
    /** /models 返回的原始 id（可能带前缀，如 anthropic/xxx）；请求时优先使用（步骤8） */
    public String rawId;
    public boolean supportsThinking;
    public boolean supportsVision;
    public boolean supportsTools = true;
    public boolean keepReasoningInHistory = false;
    public boolean needsMaxCompletionTokens = false;
    public boolean isReasoningOnly = false;
    /**
     * 自动推断出的「是否为绘图模型」（按模型名关键词猜测，仅作默认值）。
     * 实际判断请用 isImageGen()，它会优先采用用户的手动标记。
     */
    public boolean supportsImageGen = false;
    /**
     * 用户对「绘图模型」的手动标记：0=跟随自动推断，1=强制标记为绘图，2=强制取消标记。
     * 手动标记的优先级高于自动推断——中转站的模型名千奇百怪，自动识别只当默认值，
     * 漏判时用户可手动勾选，误判时用户可手动取消。
     */
    public int imageGenOverride = IMAGE_GEN_AUTO;
    public String effortDialect = "standard"; // standard | newapi

    public static final int IMAGE_GEN_AUTO = 0;
    public static final int IMAGE_GEN_ON = 1;
    public static final int IMAGE_GEN_OFF = 2;

    /** 最终判定：手动标记优先，否则用自动推断结果 */
    public boolean isImageGen() {
        if (imageGenOverride == IMAGE_GEN_ON) return true;
        if (imageGenOverride == IMAGE_GEN_OFF) return false;
        return supportsImageGen;
    }

    /** 记录用户的手动标记；与自动推断一致时回到「跟随自动」状态，避免无谓地锁死推断结果 */
    public void setImageGenTag(boolean tagged) {
        if (tagged == supportsImageGen) {
            imageGenOverride = IMAGE_GEN_AUTO;
        } else {
            imageGenOverride = tagged ? IMAGE_GEN_ON : IMAGE_GEN_OFF;
        }
    }

    public ModelDef() {
    }

    public ModelDef(String id, String displayName, String providerId,
                    boolean supportsThinking, boolean supportsVision) {
        this.id = id;
        this.displayName = displayName;
        this.providerId = providerId;
        this.supportsThinking = supportsThinking;
        this.supportsVision = supportsVision;
        this.rawId = id; // 预设模型无前缀（步骤8）；/models 拉取来的会覆盖为原始 id
    }

    /** 请求用 id（步骤8）：有原始 id 时用原始 id，否则用剥前缀 id */
    public String requestId() {
        return (rawId != null && rawId.length() > 0) ? rawId : id;
    }

    /** Builder 风格设置兼容开关 */
    public ModelDef tools(boolean v) { this.supportsTools = v; return this; }
    public ModelDef keepReasoning(boolean v) { this.keepReasoningInHistory = v; return this; }
    public ModelDef maxCompletion(boolean v) { this.needsMaxCompletionTokens = v; return this; }
    public ModelDef reasoningOnly(boolean v) { this.isReasoningOnly = v; return this; }
    public ModelDef imageGen(boolean v) { this.supportsImageGen = v; return this; }
    public ModelDef dialect(String d) { this.effortDialect = d; return this; }

    @Override
    public String toString() {
        return displayName + " (" + id + ")";
    }
}
