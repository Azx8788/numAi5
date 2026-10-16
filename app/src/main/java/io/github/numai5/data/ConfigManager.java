package io.github.numai5.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.numai5.model.Config;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.PresetModels;
import io.github.numai5.provider.Provider;

/**
 * 配置管理（apply() 而非 commit()）。
 * 多供应商：providers 以 JSON 字符串存入 SharedPreferences；旧版单供应商字段首次读取时自动迁移。
 */
public class ConfigManager {
    private static final String PREFS_NAME = "numai5_config";
    private static final String KEY_PROVIDERS = "providersJson";
    private static final String KEY_DEFAULT_MODEL = "defaultModelId";
    private static final String KEY_SYSTEM_PROMPT = "systemPrompt";
    private static final String KEY_SHRINK_THINK = "shrinkThink";
    private static final String KEY_NEWAPI_DIALECT = "newapiDialect";
    private static final String KEY_TOOL_ORDER = "toolOrder";
    // 旧版字段（仅用于一次性迁移）
    private static final String KEY_OLD_BASE_URL = "baseUrl";
    private static final String KEY_OLD_API_KEY = "apiKey";

    private static ConfigManager instance;
    private final SharedPreferences preferences;
    private final Config config;

    private ConfigManager(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        config = loadConfig();
    }

    public static synchronized ConfigManager getInstance(Context context) {
        if (instance == null) {
            instance = new ConfigManager(context);
        }
        return instance;
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("ConfigManager not initialized");
        }
        return instance;
    }

    private Gson gson() {
        return new Gson();
    }

    private Config loadConfig() {
        Config c = new Config();

        String json = preferences.getString(KEY_PROVIDERS, null);
        if (json != null && json.length() > 0) {
            try {
                List<Provider> list = gson().fromJson(json, new TypeToken<List<Provider>>() {}.getType());
                if (list != null) c.providers = list;
            } catch (Exception ignored) {
            }
        } else {
            // 迁移旧版单供应商配置
            String baseUrl = preferences.getString(KEY_OLD_BASE_URL, "");
            String apiKey = preferences.getString(KEY_OLD_API_KEY, "");
            if ((baseUrl != null && baseUrl.length() > 0) || (apiKey != null && apiKey.length() > 0)) {
                Provider p = new Provider("p1", "默认供应商");
                p.baseUrl = baseUrl == null ? "" : baseUrl;
                p.apiKey = apiKey == null ? "" : apiKey;
                c.providers.add(p);
            }
        }

        // 首次启动：预置常见供应商（地址与 Key 留空，由用户填写）
        if (c.providers.isEmpty()) {
            c.providers.addAll(PresetModels.createDefaultProviders());
        }
        for (Provider p : c.providers) {
            if (p.models == null) p.models = new ArrayList<>();
        }

        c.defaultModelId = preferences.getString(KEY_DEFAULT_MODEL, "");
        c.systemPrompt = preferences.getString(KEY_SYSTEM_PROMPT, "");
        c.shrinkThink = preferences.getBoolean(KEY_SHRINK_THINK, false);
        c.newapiDialect = preferences.getBoolean(KEY_NEWAPI_DIALECT, true);
        return c;
    }

    private void save() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(KEY_PROVIDERS, gson().toJson(config.providers));
        editor.putString(KEY_DEFAULT_MODEL, config.defaultModelId == null ? "" : config.defaultModelId);
        editor.putString(KEY_SYSTEM_PROMPT, config.systemPrompt == null ? "" : config.systemPrompt);
        editor.putBoolean(KEY_SHRINK_THINK, config.shrinkThink);
        editor.putBoolean(KEY_NEWAPI_DIALECT, config.newapiDialect);
        editor.apply();
    }

    public Config getConfig() {
        return config;
    }

    public boolean isValid() {
        return config.isValid();
    }

    public boolean isValidForFetch() {
        return config.isValidForFetch();
    }

    // ===== 供应商 =====

    public List<Provider> getProviders() {
        return config.providers;
    }

    public Provider findProvider(String id) {
        if (id == null) return null;
        for (Provider p : config.providers) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    /**
     * 按 uid（供应商 id + "/" + 模型 id）查找模型所属供应商。
     * 参数也可以是不含 "/" 的旧格式模型名——按名字在全部供应商中取第一个匹配项（兼容旧数据）。
     */
    public Provider findProviderOfModel(String uid) {
        if (uid == null) return null;
        int idx = uid.lastIndexOf('/');
        if (idx > 0 && idx < uid.length() - 1) {
            Provider owner = findProvider(uid.substring(0, idx));
            String modelId = uid.substring(idx + 1);
            if (owner != null && owner.models != null) {
                for (ModelDef m : owner.models) {
                    if (modelId.equals(m.id)) return owner;
                }
            }
        }
        for (Provider p : config.providers) {
            if (p.models == null) continue;
            for (ModelDef m : p.models) {
                if (uid.equals(m.id)) return p;
            }
        }
        return null;
    }

    /** 按 uid 查找模型；同样兼容不含 "/" 的旧格式模型名 */
    public ModelDef findModel(String uid) {
        if (uid == null) return null;
        int idx = uid.lastIndexOf('/');
        if (idx > 0 && idx < uid.length() - 1) {
            Provider owner = findProvider(uid.substring(0, idx));
            String modelId = uid.substring(idx + 1);
            if (owner != null && owner.models != null) {
                for (ModelDef m : owner.models) {
                    if (modelId.equals(m.id)) return m;
                }
            }
        }
        for (Provider p : config.providers) {
            if (p.models == null) continue;
            for (ModelDef m : p.models) {
                if (uid.equals(m.id)) return m;
            }
        }
        return null;
    }

    /** 新增供应商（自动分配 id） */
    public Provider addProvider() {
        Provider p = new Provider("p" + UUID.randomUUID().toString().substring(0, 8), "新供应商");
        config.providers.add(p);
        save();
        return p;
    }

    public void removeProvider(String id) {
        Provider p = findProvider(id);
        if (p == null) return;
        config.providers.remove(p);
        // 默认模型属于被删供应商时清空（defaultModelId 存的是 uid）
        if (config.defaultModelId != null && config.defaultModelId.length() > 0) {
            String prefix = id + "/";
            if (config.defaultModelId.startsWith(prefix)) {
                config.defaultModelId = "";
            } else if (p.models != null) {
                // 兼容旧格式（仅模型名）
                for (ModelDef m : p.models) {
                    if (config.defaultModelId.equals(m.id)) {
                        config.defaultModelId = "";
                        break;
                    }
                }
            }
        }
        save();
    }

    public void updateProvider(String id, String name, String baseUrl, String apiKey) {
        Provider p = findProvider(id);
        if (p == null) return;
        p.name = name == null ? "" : name.trim();
        p.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        p.apiKey = apiKey == null ? "" : apiKey.trim();
        save();
    }

    /**
     * 用「挑选结果」替换该供应商的模型列表。
     * 不少中转站有数百个模型，拉取后由用户勾选真正要用的几个，未勾选的会被移除。
     * 已存在模型的手动绘图标记（imageGenOverride）会被保留，避免挑选后需要重打标记。
     */
    public void applySelectedModels(String providerId, List<ModelDef> selected) {
        Provider provider = findProvider(providerId);
        if (provider == null) return;

        // 先记下旧的手动标记
        Map<String, Integer> overrides = new HashMap<>();
        if (provider.models != null) {
            for (ModelDef old : provider.models) {
                if (old != null && old.id != null && old.imageGenOverride != ModelDef.IMAGE_GEN_AUTO) {
                    overrides.put(old.id, old.imageGenOverride);
                }
            }
        }

        List<ModelDef> merged = new ArrayList<>();
        if (selected != null) {
            for (ModelDef m : selected) {
                if (m == null) continue;
                Integer override = overrides.get(m.id);
                if (override != null) m.imageGenOverride = override;
                merged.add(m);
            }
        }
        provider.models = merged;

        // 默认模型若已被移除则清空，避免指向不存在的模型
        if (config.defaultModelId != null && config.defaultModelId.length() > 0
                && findModel(config.defaultModelId) == null) {
            config.defaultModelId = "";
        }
        save();
    }

    // ===== 其他设置 =====

    public void updateDefaultModel(String v) { config.defaultModelId = v == null ? "" : v; save(); }
    public void updateSystemPrompt(String v) { config.systemPrompt = v == null ? "" : v; save(); }
    public void updateShrinkThink(boolean v) { config.shrinkThink = v; save(); }
    public void updateNewapiDialect(boolean v) { config.newapiDialect = v; save(); }

    /** 供设置页批量保存后统一刷新 */
    public void refresh() {
        save();
    }

    /** 备份恢复：整体覆盖配置 */
    public void importConfig(Config src) {
        if (src == null) return;
        List<Provider> providers = src.providers != null ? src.providers : new ArrayList<>();
        for (Provider p : providers) {
            if (p.models == null) p.models = new ArrayList<>();
            if (p.id == null || p.id.length() == 0) {
                p.id = "p" + UUID.randomUUID().toString().substring(0, 8);
            }
        }
        config.providers = providers;
        config.defaultModelId = src.defaultModelId == null ? "" : src.defaultModelId;
        config.systemPrompt = src.systemPrompt == null ? "" : src.systemPrompt;
        config.newapiDialect = src.newapiDialect;
        config.shrinkThink = src.shrinkThink;
        config.webSearchEnabled = src.webSearchEnabled;
        config.webFetchEnabled = src.webFetchEnabled;
        save();
    }

    // ===== 输入栏按钮顺序（步骤：按钮可拖动排序） =====

    public String getToolOrder() {
        return preferences.getString(KEY_TOOL_ORDER, "");
    }

    public void updateToolOrder(String order) {
        preferences.edit().putString(KEY_TOOL_ORDER, order == null ? "" : order).apply();
    }
}
