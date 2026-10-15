package io.github.numai5.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * 预设 A/B/C 三家主流模型（基于 2026 当前可用模型）。
 * 中转站 model id 可能带前缀（如 anthropic/claude-...），由 ModelCatalog 剥前缀后匹配。
 */
public class PresetModels {

    public static List<Provider> getPresetProviders() {
        List<Provider> list = new ArrayList<>();
        list.add(new Provider("A", "DeepSeek"));
        list.add(new Provider("B", "Claude & GPT"));
        list.add(new Provider("C", "Qwen"));
        return list;
    }

    public static List<ModelDef> getPresetModels() {
        List<ModelDef> list = new ArrayList<>();

        // === A: DeepSeek ===
        // DeepSeek V4 是 hybrid thinking 模型，支持 tools，多轮需回传 reasoning_content（A1）
        list.add(new ModelDef("deepseek-v4-pro", "DeepSeek V4 Pro", "A", true, false)
                .tools(true).keepReasoning(true));
        list.add(new ModelDef("deepseek-v4-flash", "DeepSeek V4 Flash", "A", true, false)
                .tools(true).keepReasoning(true));
        list.add(new ModelDef("deepseek-v4-flash-vision-exp", "DeepSeek V4 Flash Vision", "A", true, true)
                .tools(true).keepReasoning(true));

        // === B: Claude & GPT ===
        // Claude 经中转用 reasoning_effort，历史不回传 reasoning
        list.add(new ModelDef("claude-sonnet-4-6", "Claude Sonnet 4.6", "B", true, true)
                .tools(true));
        list.add(new ModelDef("claude-opus-4-8", "Claude Opus 4.8", "B", true, true)
                .tools(true));
        list.add(new ModelDef("gpt-4o", "GPT-4o", "B", false, true)
                .tools(true));
        list.add(new ModelDef("gpt-5-chat", "GPT-5 Chat", "B", false, false)
                .tools(true));
        // gpt-5.4 是 reasoning 模型，用 max_completion_tokens，不接受 temperature（A4）
        list.add(new ModelDef("gpt-5.4", "GPT-5.4", "B", true, false)
                .tools(true).maxCompletion(true).reasoningOnly(true));

        // === C: Qwen ===
        list.add(new ModelDef("qwen3.5-plus", "Qwen 3.5 Plus", "C", true, false)
                .tools(true));
        list.add(new ModelDef("qwen3.5-flash", "Qwen 3.5 Flash", "C", true, true)
                .tools(true));
        // qwq-32b 纯推理模型，不支持 function calling
        list.add(new ModelDef("qwq-32b", "Qwen QwQ 32B", "C", true, false)
                .tools(false).reasoningOnly(true));
        list.add(new ModelDef("qwen3-vl-235b", "Qwen 3 VL 235B", "C", true, true)
                .tools(true));

        return list;
    }
}
