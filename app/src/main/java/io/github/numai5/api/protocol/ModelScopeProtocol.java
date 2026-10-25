package io.github.numai5.api.protocol;

import java.util.List;
import io.github.numai5.api.ApiClient;
import io.github.numai5.api.ApiError;
import io.github.numai5.api.ApiRequest;
import io.github.numai5.api.ApiResponse;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.ModelscopePresets;
import io.github.numai5.provider.Provider;

/**
 * 魔搭（ModelScope）协议。
 *
 * 与 OpenAI 兼容层的差异集中在两处：
 * 1. /models 用 POST（实测 GET/POST 均 200，POST 与官方示例一致；404/405 时回退 GET）
 * 2. /models 不返回生图模型，需要内置清单补全（见 ModelscopePresets）
 *
 * 聊天（/chat/completions）与 SSE 解码与 OpenAI 兼容一致，直接继承；
 * errors（复数）结构已在父类解码里兼容。
 */
public class ModelScopeProtocol extends OpenAiProtocol {

    public ModelScopeProtocol(ApiClient apiClient) {
        super(apiClient);
    }

    @Override
    public String name() {
        return "modelscope";
    }

    @Override
    public List<ModelDef> fetchModels(Provider provider) throws ApiError {
        ApiResponse response;
        try {
            response = apiClient.execute(new ApiRequest("/models", "POST")
                    .setCredentials(provider.normalizedBaseUrl(), provider.apiKey)
                    .setProvider(provider)
                    .setBody("{}"));
        } catch (ApiError e) {
            // 个别部署可能只支持 GET：明确是方法不支持时回退（回退请求同样占一次供应商额度）
            String msg = e.raw != null ? e.raw.toLowerCase(java.util.Locale.ROOT) : "";
            if (e.httpCode == 404 || e.httpCode == 405
                    || msg.contains("405") || msg.contains("404")
                    || msg.contains("method not allowed")) {
                response = apiClient.execute(new ApiRequest("/models", "GET")
                        .setCredentials(provider.normalizedBaseUrl(), provider.apiKey)
                        .setProvider(provider));
            } else {
                throw e;
            }
        }

        List<ModelDef> models = parseModelsResponse(provider, response);
        // 魔搭 /v1/models 只返回对话模型，生图模型不在其中，这里补进候选列表
        for (ModelDef image : ModelscopePresets.imageModels(provider.id)) {
            boolean exists = false;
            for (ModelDef m : models) {
                if (image.id != null && image.id.equals(m.id)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) models.add(image);
        }
        return models;
    }
}
