package io.github.numai5.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型目录工具：统一从 ConfigManager 读取供应商与模型；/models 结果解析与能力推断。
 * 数据来源唯一为 ConfigManager（多供应商），本类不再自行持有列表。
 */
public class ModelCatalog {
    private static ModelCatalog instance;

    private ModelCatalog() {
    }

    public static synchronized ModelCatalog getInstance() {
        if (instance == null) {
            instance = new ModelCatalog();
        }
        return instance;
    }

    // ===== 查询（代理到 ConfigManager） =====

    public List<Provider> getProviders() {
        return io.github.numai5.data.ConfigManager.getInstance().getProviders();
    }

    public Provider findProvider(String id) {
        return io.github.numai5.data.ConfigManager.getInstance().findProvider(id);
    }

    /** 按 uid（providerId/id）查供应商；兼容不含 "/" 的旧格式模型名 */
    public Provider findProviderOfModel(String uid) {
        return io.github.numai5.data.ConfigManager.getInstance().findProviderOfModel(uid);
    }

    /** 跨全部供应商的所有模型（标识请用 ModelDef#uid，不要只按模型名） */
    public List<ModelDef> getAllModels() {
        List<ModelDef> all = new ArrayList<>();
        for (Provider p : getProviders()) {
            if (p.models != null) all.addAll(p.models);
        }
        return all;
    }

    public List<ModelDef> getModelsByProvider(String providerId) {
        Provider p = findProvider(providerId);
        return p != null && p.models != null ? new ArrayList<>(p.models) : new ArrayList<ModelDef>();
    }

    /** 按 uid（providerId/id）查模型；兼容不含 "/" 的旧格式模型名 */
    public ModelDef getModel(String uid) {
        return io.github.numai5.data.ConfigManager.getInstance().findModel(uid);
    }

    public boolean hasModel(String uid) {
        return getModel(uid) != null;
    }

    /** 是否已存在同名（剥前缀）模型 */
    public boolean hasModelIdInProvider(Provider provider, String id) {
        if (provider == null || provider.models == null || id == null) return false;
        for (ModelDef m : provider.models) {
            if (id.equals(m.id)) return true;
        }
        return false;
    }

    // ===== /models 解析 =====

    /** 剥前缀：anthropic/claude-xxx → claude-xxx */
    public static String stripPrefix(String rawId) {
        if (rawId == null) return "";
        int idx = rawId.lastIndexOf('/');
        if (idx >= 0 && idx < rawId.length() - 1) {
            return rawId.substring(idx + 1);
        }
        return rawId;
    }

    /**
     * 解析 /models 接口返回的 data 数组（仅返回解析结果，落库由 ConfigManager 负责）。
     */
    public List<ModelDef> parseModelsEndpoint(JsonArray data, String providerId) {
        List<ModelDef> result = new ArrayList<>();
        if (data == null) return result;
        for (int i = 0; i < data.size(); i++) {
            JsonObject obj = data.get(i).getAsJsonObject();
            if (obj == null || !obj.has("id")) continue;
            String rawId = obj.get("id").getAsString();
            String id = stripPrefix(rawId);
            if (id.length() == 0) continue;

            ModelDef m = new ModelDef(id, id, providerId, false, false);
            m.rawId = rawId;
            inferCapabilities(m, rawId);
            if (m.isThinking() && m.isVision()) m.displayName += " (🧠📷)";
            else if (m.isThinking()) m.displayName += " (🧠)";
            else if (m.isVision()) m.displayName += " (📷)";
            result.add(m);
        }
        return result;
    }

    /** 根据模型 id 推断能力开关（规则在 ModelRegistry，这里是兼容入口） */
    public void inferCapabilities(ModelDef m, String rawId) {
        ModelRegistry.applyTo(m, rawId);
    }

    /** 汇总所有供应商中的绘图模型（手动标记优先） */
    public List<ModelDef> getImageGenModels() {
        List<ModelDef> list = new ArrayList<>();
        for (ModelDef m : getAllModels()) {
            if (m.isImageGen()) list.add(m);
        }
        return list;
    }
}
