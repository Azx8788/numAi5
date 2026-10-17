package io.github.numai5.api.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import io.github.numai5.api.ApiAuth;
import io.github.numai5.api.ApiClient;
import io.github.numai5.api.ApiCompatHeaders;
import io.github.numai5.api.ApiError;
import io.github.numai5.api.ApiRequest;
import io.github.numai5.api.ApiResponse;
import io.github.numai5.api.JsonUtil;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;
import okhttp3.Request;

/**
 * OpenAI 兼容协议（绝大多数中转站的默认形态）。
 * 逻辑从 ApiService 原样搬迁，行为不变：GET /models、标准 SSE 解码、
 * error/errors 双兼容、站点指纹兼容头。
 */
public class OpenAiProtocol implements ProviderProtocol {

    protected final ApiClient apiClient;

    public OpenAiProtocol(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public String chatUrl(Provider provider) {
        return provider.normalizedBaseUrl() + "/chat/completions";
    }

    @Override
    public void applyHeaders(Request.Builder builder, Provider provider, String url) {
        builder.header("Authorization", "Bearer " + ApiAuth.sanitize(provider.apiKey));
        builder.header("Content-Type", "application/json");
        // 已知会对客户端做指纹校验的站点（如 agentrouter.org），自动补上兼容头
        ApiCompatHeaders.apply(builder, url);
    }

    @Override
    public List<ModelDef> fetchModels(Provider provider) throws ApiError {
        String url = provider.normalizedBaseUrl() + "/models";
        ApiRequest request = new ApiRequest("/models", "GET")
                .setCredentials(provider.normalizedBaseUrl(), provider.apiKey);
        ApiResponse response = apiClient.execute(request);
        return parseModelsResponse(provider, response);
    }

    /** /models 响应解析（宽松 JSON + 可定位错误文案） */
    protected List<ModelDef> parseModelsResponse(Provider provider, ApiResponse response) throws ApiError {
        String url = provider.normalizedBaseUrl() + "/models";
        // 宽松解析：部分站点返回的 JSON 并不规范（未加引号的键等），严格模式会直接抛
        // "Use JsonReader.setLenient(true)..."；真的不是 JSON（如 HTML 错误页）时给出可定位提示
        JsonElement root;
        try {
            root = JsonUtil.parseLenient(response.body);
        } catch (Exception parseFailure) {
            throw new ApiError(describeBadModelsResponse(url, response, "返回内容不是有效的 JSON"));
        }
        if (root == null || !root.isJsonObject()) {
            throw new ApiError(describeBadModelsResponse(url, response, "响应不是 JSON 对象"));
        }
        JsonArray data = root.getAsJsonObject().getAsJsonArray("data");
        if (data == null) {
            throw new ApiError(describeBadModelsResponse(url, response,
                    "响应里没有 data 数组（该站可能不支持 /models）"));
        }
        return ModelCatalog.getInstance().parseModelsEndpoint(data, provider.id);
    }

    /** 拉取模型失败时拼装可读原因：请求地址 + HTTP 状态 + 实际返回片段 */
    protected String describeBadModelsResponse(String url, ApiResponse response, String reason) {
        StringBuilder sb = new StringBuilder(reason);
        sb.append("；请求 ").append(url)
                .append(" 返回（HTTP ").append(response.statusCode).append("）：")
                .append(JsonUtil.snippet(response.body));
        if (!JsonUtil.looksLikeJson(response.body)) {
            sb.append("。请检查中转地址是否以 /v1 结尾、该站是否提供 /models 接口");
        }
        return sb.toString();
    }

    @Override
    public ChunkDelta decodeChunk(JsonObject chunk) {
        ChunkDelta delta = new ChunkDelta();
        if (!chunk.has("choices")) return delta;
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) return delta;

        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonElement finishReason = choice.get("finish_reason");
        if (finishReason != null && !finishReason.isJsonNull()) {
            delta.finishReason = finishReason.getAsString();
        }

        JsonElement deltaEl = choice.get("delta");
        if (deltaEl == null || deltaEl.isJsonNull() || !deltaEl.isJsonObject()) return delta;
        JsonObject d = deltaEl.getAsJsonObject();

        if (d.has("reasoning_content") && !d.get("reasoning_content").isJsonNull()) {
            delta.reasoning = d.get("reasoning_content").getAsString();
        }
        if (d.has("content") && !d.get("content").isJsonNull()) {
            delta.content = d.get("content").getAsString();
        }

        // 工具调用流式聚合
        if (d.has("tool_calls") && !d.get("tool_calls").isJsonNull()) {
            JsonArray tcArr = d.getAsJsonArray("tool_calls");
            for (int i = 0; i < tcArr.size(); i++) {
                JsonObject tcObj = tcArr.get(i).getAsJsonObject();
                ChunkDelta.ToolCallDelta tcd = new ChunkDelta.ToolCallDelta();
                if (tcObj.has("id") && !tcObj.get("id").isJsonNull()) {
                    tcd.id = tcObj.get("id").getAsString();
                }
                tcd.index = tcObj.has("index") ? tcObj.get("index").getAsInt() : 0;
                if (tcObj.has("function")) {
                    JsonObject fn = tcObj.getAsJsonObject("function");
                    if (fn.has("name") && !fn.get("name").isJsonNull()) {
                        tcd.name = fn.get("name").getAsString();
                    }
                    if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                        tcd.arguments = fn.get("arguments").getAsString();
                    }
                }
                delta.toolCalls.add(tcd);
            }
        }
        return delta;
    }

    @Override
    public String decodeChunkError(JsonObject chunk) {
        // 魔搭用 errors（复数），OpenAI 系用 error（单数），两者都认
        for (String key : new String[]{"error", "errors"}) {
            JsonElement err = chunk.get(key);
            if (err == null || err.isJsonNull()) continue;
            if (err.isJsonObject() && err.getAsJsonObject().has("message")
                    && !err.getAsJsonObject().get("message").isJsonNull()) {
                return err.getAsJsonObject().get("message").getAsString();
            }
            if (err.isJsonPrimitive()) {
                return err.getAsString();
            }
            return err.toString();
        }
        return chunk.toString();
    }
}
