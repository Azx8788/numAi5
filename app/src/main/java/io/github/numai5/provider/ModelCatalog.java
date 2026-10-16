package io.github.numai5.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理所有可用模型：预设 + /models 拉取合并 + 剥前缀去重 + 能力推断（B1）。
 */
public class ModelCatalog {
    private static ModelCatalog instance;

    private final List<Provider> providers = new ArrayList<>();
    private final Map<String, ModelDef> models = new LinkedHashMap<>(); // id -> ModelDef

    private ModelCatalog() {
        providers.addAll(PresetModels.getPresetProviders());
        for (ModelDef m : PresetModels.getPresetModels()) {
            models.put(m.id, m);
        }
    }

    public static synchronized ModelCatalog getInstance() {
        if (instance == null) {
            instance = new ModelCatalog();
        }
        return instance;
    }

    public List<Provider> getProviders() {
        return new ArrayList<>(providers);
    }

    public Provider findProvider(String id) {
        for (Provider p : providers) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    public List<ModelDef> getAllModels() {
        return new ArrayList<>(models.values());
    }

    public ModelDef getModel(String id) {
        if (id == null) return null;
        return models.get(stripPrefix(id));
    }

    public boolean hasModel(String id) {
        return id != null && models.containsKey(stripPrefix(id));
    }

    public List<ModelDef> getModelsByProvider(String providerId) {
        List<ModelDef> list = new ArrayList<>();
        for (ModelDef m : models.values()) {
            if (m.providerId.equals(providerId)) list.add(m);
        }
        return list;
    }

    /**
     * 合并 /models 返回（B1）：剥前缀、按能力推断、合并去重。
     * @param data OpenAI /models 接口的 data 数组
     */
    public void mergeFromModelsEndpoint(JsonArray data) {
        if (data == null) return;
        for (int i = 0; i < data.size(); i++) {
            JsonObject obj = data.get(i).getAsJsonObject();
            if (obj == null || !obj.has("id")) continue;
            String rawId = obj.get("id").getAsString();
            String id = stripPrefix(rawId);

            ModelDef existing = models.get(id);
            if (existing != null) {
                // 拉取到的更权威：更新能力开关，但保留 preset 的 provider/名称
                existing.rawId = rawId; // 步骤8：请求时优先用原始 id
                inferCapabilities(existing, rawId);
                if (obj.has("displayName")) {
                    existing.displayName = obj.get("displayName").getAsString();
                }
                continue;
            }

            // 新模型
            ModelDef m = new ModelDef(id, id, inferProvider(rawId), false, false);
            m.rawId = rawId; // 步骤8：请求时优先用原始 id
            inferCapabilities(m, rawId);
            // 若推断出 thinking/vision，更新显示名加后缀
            if (m.supportsThinking && m.supportsVision) m.displayName += " (🧠)";
            else if (m.supportsThinking) m.displayName += " (🧠)";
            else if (m.supportsVision) m.displayName += " (📷)";
            models.put(id, m);
        }
    }

    /** 剥前缀：anthropic/claude-xxx → claude-xxx */
    private String stripPrefix(String rawId) {
        if (rawId == null) return "";
        int idx = rawId.lastIndexOf('/');
        if (idx >= 0 && idx < rawId.length() - 1) {
            return rawId.substring(idx + 1);
        }
        return rawId;
    }

    /** 根据前缀推断供应商 */
    private String inferProvider(String rawId) {
        String lower = rawId.toLowerCase();
        if (lower.startsWith("deepseek")) return "A";
        if (lower.startsWith("anthropic") || lower.startsWith("claude") ||
                lower.startsWith("openai") || lower.startsWith("gpt")) return "B";
        if (lower.startsWith("qwen")) return "C";
        return "A"; // 默认
    }

    /** 根据模型 id 推断能力（B1） */
    private void inferCapabilities(ModelDef m, String rawId) {
        String lower = rawId.toLowerCase();

        // 思考能力
        if (lower.contains("reasoner") || lower.contains("r1") ||
                lower.contains("thinking") || lower.contains("qwq") ||
                lower.contains("o1") || lower.contains("o3") ||
                lower.contains("opus") || lower.contains("sonnet") ||
                lower.contains("deepseek-v4") || lower.contains("qwen3.5") ||
                lower.contains("qwen3-vl") || lower.contains("gpt-5.4")) {
            m.supportsThinking = true;
        }

        // 视觉能力
        if (lower.contains("vision") || lower.contains("vl") ||
                lower.contains("gpt-4o") || lower.contains("claude") ||
                lower.contains("gemini") || lower.contains("qwen3-vl") ||
                lower.contains("deepseek-v4-flash-vision")) {
            m.supportsVision = true;
        }

        // 不支持 function calling 的黑名单（B5）
        if (lower.contains("deepseek-reasoner") || lower.contains("qwq")) {
            m.supportsTools = false;
        }

        // DeepSeek V4 hybrid + tools 需回传 reasoning_content（A1）
        if (lower.contains("deepseek-v4") && m.supportsTools) {
            m.keepReasoningInHistory = true;
        }

        // o系/gpt-5 用 max_completion_tokens（A4）
        if (lower.contains("o1") || lower.contains("o3") || lower.contains("gpt-5")) {
            m.needsMaxCompletionTokens = true;
        }

        // 纯推理模型不接受 temperature
        if (lower.contains("o1") || lower.contains("o3") || lower.contains("gpt-5.4") ||
                lower.contains("qwq")) {
            m.isReasoningOnly = true;
        }
    }
}
