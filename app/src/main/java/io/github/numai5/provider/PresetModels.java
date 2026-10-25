package io.github.numai5.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * 首次启动预置的供应商与模型（地址/Key 留空，模型列表可被 /models 拉取结果补充）。
 * 用户也可在设置页新增任意多个供应商。
 */
public class PresetModels {

    public static List<Provider> createDefaultProviders() {
        List<Provider> list = new ArrayList<>();

        Provider deepseek = new Provider("p1", "DeepSeek");
        deepseek.models.add(new ModelDef("deepseek-v4-pro", "DeepSeek V4 Pro", "p1", true, false)
                .tools(true).keepReasoning(true));
        deepseek.models.add(new ModelDef("deepseek-v4-flash", "DeepSeek V4 Flash", "p1", true, false)
                .tools(true).keepReasoning(true));
        deepseek.models.add(new ModelDef("deepseek-v4-flash-vision-exp", "DeepSeek V4 Flash Vision", "p1", true, true)
                .tools(true).keepReasoning(true));
        list.add(deepseek);

        Provider claudeGpt = new Provider("p2", "Claude & GPT");
        claudeGpt.models.add(new ModelDef("claude-sonnet-4-6", "Claude Sonnet 4.6", "p2", true, true)
                .tools(true));
        claudeGpt.models.add(new ModelDef("claude-opus-4-8", "Claude Opus 4.8", "p2", true, true)
                .tools(true));
        claudeGpt.models.add(new ModelDef("gpt-4o", "GPT-4o", "p2", false, true)
                .tools(true));
        claudeGpt.models.add(new ModelDef("gpt-5-chat", "GPT-5 Chat", "p2", false, false)
                .tools(true));
        claudeGpt.models.add(new ModelDef("gpt-5.4", "GPT-5.4", "p2", true, false)
                .tools(true).maxCompletion(true).reasoningOnly(true));
        list.add(claudeGpt);

        Provider qwen = new Provider("p3", "Qwen");
        qwen.models.add(new ModelDef("qwen3.5-plus", "Qwen 3.5 Plus", "p3", true, false)
                .tools(true));
        qwen.models.add(new ModelDef("qwen3.5-flash", "Qwen 3.5 Flash", "p3", true, true)
                .tools(true));
        qwen.models.add(new ModelDef("qwq-32b", "Qwen QwQ 32B", "p3", true, false)
                .tools(false).reasoningOnly(true));
        qwen.models.add(new ModelDef("qwen3-vl-235b", "Qwen 3 VL 235B", "p3", true, true)
                .tools(true));
        list.add(qwen);

        // 魔搭社区：官方固定地址，用户只需填访问令牌（ms- 开头）。
        // 生图模型不在 /v1/models 返回里，单独内置（见 ModelscopePresets）。
        Provider modelscope = new Provider("p4", "魔搭 ModelScope");
        modelscope.type = Provider.TYPE_MODELSCOPE;
        modelscope.baseUrl = "https://api-inference.modelscope.cn/v1";
        modelscope.models.addAll(ModelscopePresets.chatModels());
        modelscope.models.addAll(ModelscopePresets.imageModels());
        list.add(modelscope);

        // 商汤日日新：对话走 OpenAI 兼容；U1.5 系列是同步生图/图生图模型，单独标记
        Provider sensenova = new Provider("p5", "商汤日日新 SenseNova");
        sensenova.type = Provider.TYPE_SENSENOVA;
        sensenova.baseUrl = "https://token.sensenova.cn/v1";
        sensenova.models.add(new ModelDef("sensenova-6.8-flash-lite", "SenseNova 6.8 Flash Lite", "p5", true, true)
                .tools(true));
        sensenova.models.add(new ModelDef("deepseek-v4-flash", "DeepSeek V4 Flash", "p5", true, false)
                .tools(true).keepReasoning(true));
        sensenova.models.add(new ModelDef("deepseek-flash", "DeepSeek V4.1 Flash", "p5", true, true)
                .tools(true).keepReasoning(true));
        sensenova.models.add(new ModelDef("glm-5.2", "GLM-5.2", "p5", true, false)
                .tools(true));
        sensenova.models.add(new ModelDef("kimi-k3", "Kimi K3", "p5", true, true)
                .tools(true));
        sensenova.models.add(imageModel("p5", "sensenova-u1.5-lite", "SenseNova U1.5 Lite（绘图）"));
        sensenova.models.add(imageModel("p5", "sensenova-u1.5-fast", "SenseNova U1.5 Fast（绘图）"));
        list.add(sensenova);

        return list;
    }

    /** 生图模型：强制标记为绘图（不参与对话模型选择） */
    private static ModelDef imageModel(String providerId, String id, String displayName) {
        ModelDef m = new ModelDef(id, displayName, providerId, false, false);
        m.supportsImageGen = true;
        m.imageGenOverride = ModelDef.IMAGE_GEN_ON;
        return m;
    }
}
