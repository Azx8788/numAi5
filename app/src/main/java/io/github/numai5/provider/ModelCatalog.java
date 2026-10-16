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

    public Provider findProviderOfModel(String modelId) {
        return io.github.numai5.data.ConfigManager.getInstance().findProviderOfModel(modelId);
    }

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

    public ModelDef getModel(String id) {
        return io.github.numai5.data.ConfigManager.getInstance().findModel(id);
    }

    public boolean hasModel(String id) {
        return getModel(id) != null;
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
            if (m.supportsThinking && m.supportsVision) m.displayName += " (🧠📷)";
            else if (m.supportsThinking) m.displayName += " (🧠)";
            else if (m.supportsVision) m.displayName += " (📷)";
            result.add(m);
        }
        return result;
    }

    /** 根据模型 id 推断能力开关 */
    public void inferCapabilities(ModelDef m, String rawId) {
        if (m == null || rawId == null) return;
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

        // 图像生成模型（自动推断，仅作默认值；用户可在设置里手动标记覆盖）
        if (lower.contains("dall-e") || lower.contains("gpt-image") || lower.contains("imagen")
                || lower.contains("flux") || lower.contains("stable-diffusion")
                || lower.contains("sdxl") || lower.contains("sd3") || lower.contains("midjourney")
                || lower.contains("seedream") || lower.contains("seededit")
                || lower.contains("kolors") || lower.contains("cogview") || lower.contains("wanx")
                || lower.contains("hunyuan-image") || lower.contains("qwen-image")
                || lower.contains("imagegen") || lower.contains("image-gen")
                || lower.contains("-image") || lower.contains("nano-banana")
                || lower.contains("ideogram") || lower.contains("recraft")
                || lower.contains("doubao") || lower.contains("t2i")
                || lower.contains("text-to-image") || lower.contains("文生图")
                || lower.contains("绘图") || lower.contains("画图")) {
            m.supportsImageGen = true;
        }
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
