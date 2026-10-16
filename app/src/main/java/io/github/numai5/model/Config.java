package io.github.numai5.model;

/** 应用配置 POJO。会被 Gson 在内存中使用；持久化由 ConfigManager 字段级负责。 */
public class Config {
    public String baseUrl = "";
    public String apiKey = "";
    public String defaultModelId = "";
    public String systemPrompt = "";
    public boolean shrinkThink = false;
    public boolean newapiDialect = true;   // 步骤5：默认按 NewAPI 方言（不支持 max 时自动降级为 high）
    public boolean webSearchEnabled = false;
    public boolean webFetchEnabled = false;

    /** 步骤9：只校验中转地址与 Key，供首次拉取模型使用（不要求已保存默认模型）。 */
    public boolean isValidForFetch() {
        return baseUrl != null && baseUrl.length() > 0
                && apiKey != null && apiKey.length() > 0;
    }

    public boolean isValid() {
        return isValidForFetch()
                && defaultModelId != null && defaultModelId.length() > 0;
    }
}
