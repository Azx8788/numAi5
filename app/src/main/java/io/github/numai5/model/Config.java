package io.github.numai5.model;

import java.util.ArrayList;
import java.util.List;
import io.github.numai5.provider.Provider;

/** 应用配置 POJO。持久化由 ConfigManager 负责（providers 以 JSON 存储）。 */
public class Config {
    /** 供应商列表（不限个数，各自独立地址/Key/模型） */
    public List<Provider> providers = new ArrayList<>();
    public String defaultModelId = "";
    public String systemPrompt = "";
    public boolean shrinkThink = false;
    public boolean newapiDialect = true;   // 默认按 NewAPI 方言（不支持 max 时自动降级为 high）
    public boolean webSearchEnabled = false;
    public boolean webFetchEnabled = false;
    public boolean memoryEnabled = true;   // 本地记忆是否参与对话

    /** 只要有一个供应商填好地址与 Key 即可拉取模型 */
    public boolean isValidForFetch() {
        for (Provider p : providers) {
            if (p != null && p.isValidForFetch()) return true;
        }
        return false;
    }
}
