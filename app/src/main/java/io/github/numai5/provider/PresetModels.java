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

        return list;
    }
}
