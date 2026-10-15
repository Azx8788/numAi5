package io.github.numai5.model;

/** 应用配置 POJO。会被 Gson 在内存中使用；持久化由 ConfigManager 字段级负责。 */
public class Config {
    public String baseUrl = "";
    public String apiKey = "";
    public String defaultModelId = "";
    public String systemPrompt = "";
    public boolean shrinkThink = false;
    public boolean newapiDialect = false;   // 中转带 Thinking 兼容层时启用（B1）
    public boolean webSearchEnabled = false;
    public boolean webFetchEnabled = false;

    public boolean isValid() {
        return baseUrl != null && baseUrl.length() > 0
                && apiKey != null && apiKey.length() > 0
                && defaultModelId != null && defaultModelId.length() > 0;
    }
}
