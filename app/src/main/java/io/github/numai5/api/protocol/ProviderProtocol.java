package io.github.numai5.api.protocol;

import com.google.gson.JsonObject;
import java.util.List;
import io.github.numai5.api.ApiError;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;
import okhttp3.Request;

/**
 * 供应商协议适配器（借鉴 RikkaHub「每供应商一个实现、协议差异不进公共层」的思路）。
 *
 * 三件事统一收口：
 * 1. 发送——聊天地址、鉴权与站点特殊头（如 agentrouter.org 的客户端指纹头）
 * 2. 接收——流式 chunk / 流中错误对象的解码
 * 3. 拉模型——/models 的请求方式与结果解析（含魔搭的生图模型补全）
 *
 * 公共层（ApiService）只做编排：重试、工具循环、上下文裁剪、回调。
 */
public interface ProviderProtocol {

    /** 协议名（调试/日志用） */
    String name();

    /** 聊天接口完整地址（默认 baseUrl + /chat/completions） */
    String chatUrl(Provider provider);

    /** 应用鉴权头与站点特殊头（实现方内部调用 ApiCompatHeaders 等） */
    void applyHeaders(Request.Builder builder, Provider provider, String url);

    /** 拉取模型列表（HTTP + 解析 + 可读错误；不落库，由调用方决定添加哪些） */
    List<ModelDef> fetchModels(Provider provider) throws ApiError;

    /** 解码一条 SSE chunk */
    ChunkDelta decodeChunk(JsonObject chunk);

    /** 解码流中错误对象为可读文案（OpenAI 的 error / 魔搭的 errors） */
    String decodeChunkError(JsonObject chunk);
}
