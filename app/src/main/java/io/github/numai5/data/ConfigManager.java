package io.github.numai5.data;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.numai5.model.Config;

/**
 * 配置管理（C 类修复：主线程用 apply() 而非 commit()）。
 */
public class ConfigManager {
    private static final String PREFS_NAME = "numai5_config";
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_DEFAULT_MODEL = "defaultModelId";
    private static final String KEY_SYSTEM_PROMPT = "systemPrompt";
    private static final String KEY_SHRINK_THINK = "shrinkThink";
    private static final String KEY_NEWAPI_DIALECT = "newapiDialect";

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

    private Config loadConfig() {
        Config c = new Config();
        c.baseUrl = preferences.getString(KEY_BASE_URL, "");
        c.apiKey = preferences.getString(KEY_API_KEY, "");
        c.defaultModelId = preferences.getString(KEY_DEFAULT_MODEL, "");
        c.systemPrompt = preferences.getString(KEY_SYSTEM_PROMPT, "");
        c.shrinkThink = preferences.getBoolean(KEY_SHRINK_THINK, false);
        c.newapiDialect = preferences.getBoolean(KEY_NEWAPI_DIALECT, true); // 步骤5：默认按 NewAPI 方言
        return c;
    }

    private void save() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(KEY_BASE_URL, config.baseUrl == null ? "" : config.baseUrl);
        editor.putString(KEY_API_KEY, config.apiKey == null ? "" : config.apiKey);
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

    /** 步骤9：拉取模型只需要中转地址与 Key */
    public boolean isValidForFetch() {
        return config.isValidForFetch();
    }

    public void updateBaseUrl(String v) { config.baseUrl = v; save(); }
    public void updateApiKey(String v) { config.apiKey = v; save(); }
    public void updateDefaultModel(String v) { config.defaultModelId = v; save(); }
    public void updateSystemPrompt(String v) { config.systemPrompt = v; save(); }
    public void updateShrinkThink(boolean v) { config.shrinkThink = v; save(); }
    public void updateNewapiDialect(boolean v) { config.newapiDialect = v; save(); }
}
