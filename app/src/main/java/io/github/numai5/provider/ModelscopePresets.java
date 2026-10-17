package io.github.numai5.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * 魔搭（ModelScope）API-Inference 的专属预设。
 *
 * 与普通 OpenAI 兼容中转站的两处关键差异：
 * 1. 生图是异步任务制：提交 POST /images/generations（带 X-ModelScope-Async-Mode: true）
 *    拿到 task_id，再轮询 GET /tasks/{task_id}（带 X-ModelScope-Task-Type: image_generation），
 *    task_status 变为 SUCCEED 后从 output_images 取图；提交响应里的 task_status 不可信。
 * 2. /v1/models 只返回对话模型，生图模型不在其中，必须内置清单。
 *
 * 下列生图模型均于 2026-10-17 实测可提交成功并产出图片。
 */
public final class ModelscopePresets {

    /** 默认生图模型（出图稳定、速度快） */
    public static final String DEFAULT_IMAGE_MODEL = "Qwen/Qwen-Image";

    /** 常用对话模型（拉取失败时兜底，正常以 /v1/models 为准） */
    public static List<ModelDef> chatModels() {
        List<ModelDef> list = new ArrayList<>();
        list.add(new ModelDef("DeepSeek-V4-Flash-0731", "DeepSeek V4 Flash", "p4", true, false)
                .tools(true).keepReasoning(true));
        list.add(new ModelDef("DeepSeek-V4-Pro", "DeepSeek V4 Pro", "p4", true, false)
                .tools(true).keepReasoning(true));
        // 魔搭请求需要完整仓库名（含 org），rawId 保留原始 id
        for (ModelDef m : list) {
            if ("DeepSeek-V4-Flash-0731".equals(m.id)) {
                m.rawId = "deepseek-ai/DeepSeek-V4-Flash-0731";
            } else if ("DeepSeek-V4-Pro".equals(m.id)) {
                m.rawId = "deepseek-ai/DeepSeek-V4-Pro";
            }
        }
        return list;
    }

    /** 生图模型清单（/v1/models 不返回，固定内置） */
    public static List<ModelDef> imageModels() {
        return imageModels("p4");
    }

    /** 生图模型清单，providerId 由调用方指定（拉取模型后合并进候选列表时用） */
    public static List<ModelDef> imageModels(String providerId) {
        List<ModelDef> list = new ArrayList<>();
        list.add(imageModel(providerId, "Qwen-Image", "Qwen/Qwen-Image", "Qwen-Image（通用）"));
        list.add(imageModel(providerId, "Qwen-Image-2512", "Qwen/Qwen-Image-2512", "Qwen-Image 2512"));
        list.add(imageModel(providerId, "Z-Image-Turbo", "Tongyi-MAI/Z-Image-Turbo", "Z-Image Turbo（快）"));
        list.add(imageModel(providerId, "majicflus_v1", "MAILAND/majicflus_v1", "MajicFlus v1"));
        list.add(imageModel(providerId, "Qwen-Image-Edit", "Qwen/Qwen-Image-Edit", "Qwen-Image Edit（改图）"));
        return list;
    }

    private static ModelDef imageModel(String providerId, String id, String rawId, String displayName) {
        ModelDef m = new ModelDef(id, displayName, providerId, false, false);
        m.rawId = rawId;           // 请求时必须用带 org 的完整 id
        m.supportsImageGen = true; // 自动识别为绘图模型
        m.imageGenOverride = ModelDef.IMAGE_GEN_ON;
        return m;
    }

    private ModelscopePresets() {
    }
}
