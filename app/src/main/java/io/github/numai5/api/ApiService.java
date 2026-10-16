package io.github.numai5.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import io.github.numai5.data.ConfigManager;
import io.github.numai5.data.ContextTrimmer;
import io.github.numai5.data.MessageManager;
import io.github.numai5.model.Chat;
import io.github.numai5.model.Config;
import io.github.numai5.model.Message;
import io.github.numai5.model.Role;
import io.github.numai5.model.ThinkingLevel;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;
import io.github.numai5.thinking.ThinkingMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OpenAI 兼容 Chat Completions 服务。
 * 集中处理 buildMessages、SSE 流式、工具循环、400 自愈（A1）。
 */
public class ApiService {
    private static final String TAG = "ApiService";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 本会话内已确认不支持 max 的模型 id（步骤5）：之后 MAX 直接降级 high，不再发送 max */
    private static final Set<String> maxUnsupportedModels =
            Collections.synchronizedSet(new HashSet<>());

    private final Context context;
    private final ApiClient apiClient;
    private final ConfigManager configManager;
    private final Handler mainHandler;
    private final OkHttpClient sseClient;

    /** 进行中的网络调用（步骤12 请求取消） */
    private volatile Call activeCall;
    private volatile boolean cancelled;

    public ApiService(Context context) {
        this.context = resolveAppContext(context);
        this.apiClient = new ApiClient();
        this.configManager = ConfigManager.getInstance(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.sseClient = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 兜底：若传入的是尚未 attach 的 Activity（例如误在字段初始化里 new ApiService(this)），
     * getApplicationContext() 会抛 NPE。此时退回使用传入的 Context 而不是崩溃。
     * 正确做法仍是在 onCreate 之后创建，避免持有 Activity 引用。
     */
    private static Context resolveAppContext(Context context) {
        if (context == null) return null;
        try {
            Context app = context.getApplicationContext();
            return app != null ? app : context;
        } catch (Exception e) {
            Log.e(TAG, "context not attached yet, fallback to given context", e);
            return context;
        }
    }

    /**
     * 发起一轮对话（步骤2：assistantMsg 由调用方创建并加入会话，便于 UI 轮询流式刷新）。
     * 回调统一切到主线程；中断时保留已收到的部分内容并标记 isError（B4）。
     */
    public void chatCompletion(Chat chat, Message currentUserMessage, ModelDef model, ThinkingLevel level,
                               Message assistantMsg, ApiCallback<ChatCompletionResult> callback) {
        cancelled = false;
        new Thread(() -> doChatCompletion(chat, currentUserMessage, model, level, assistantMsg, callback, 0, false)).start();
    }

    /** 取消进行中的请求（步骤12）：当前调用立即断开，回合以「中断」结束并保留已收内容 */
    public void cancel() {
        cancelled = true;
        Call call = activeCall;
        if (call != null) {
            call.cancel();
        }
    }

    private void doChatCompletion(Chat chat, Message currentUserMessage, ModelDef model, ThinkingLevel level,
                                  Message assistantMsg, ApiCallback<ChatCompletionResult> callback,
                                  int reasoningRetries, boolean maxRetryUsed) {
        Config config = configManager.getConfig();
        // 用 uid 定位供应商：同名模型可能出现在多个中转站，只按模型名会串到别家
        Provider provider = ModelCatalog.getInstance().findProviderOfModel(model.uid());
        if (provider == null) {
            postError(callback, new ApiError("未找到模型所属的供应商，请到设置中检查"));
            return;
        }
        if (!provider.isValidForFetch()) {
            postError(callback, new ApiError("供应商「" + provider.displayName() + "」未配置中转地址或 Key"));
            return;
        }
        String baseUrl = provider.normalizedBaseUrl();
        String apiKey = provider.apiKey;
        String modelId = model.requestId(); // 步骤8：优先使用 /models 返回的原始 id

        if (cancelled) {
            handleCancelled(assistantMsg, callback);
            return;
        }

        try {
            // 排除本轮占位的 assistant 消息，避免把空回复发进请求历史
            List<Message> source = new ArrayList<>();
            for (Message m : chat.messages) {
                if (m != assistantMsg) source.add(m);
            }
            List<Message> history = new ArrayList<>();
            boolean trimmed = ContextTrimmer.trim(source, history);
            if (currentUserMessage != null && !history.contains(currentUserMessage)) {
                history.add(currentUserMessage); // 容错：调用方未把当前消息并入历史时兜底
            }
            // 系统提示词来自设置页：仅在请求上下文注入，不改动本地聊天历史；已有 system 消息时不重复
            String sysPrompt = config.systemPrompt != null ? config.systemPrompt.trim() : "";
            if (sysPrompt.length() > 0 && !containsSystemMessage(history)) {
                history.add(0, MessageManager.createSystemMessage(sysPrompt));
            }

            JsonArray messages = buildMessages(model, history, currentUserMessage);

            JsonObject payload = new JsonObject();
            payload.addProperty("model", modelId);
            payload.add("messages", messages);
            payload.addProperty("stream", true);

            // 思考等级（步骤5）：方言开关从配置读取（默认 NewAPI 方言）
            String effort = null;
            if (model.supportsThinking) {
                effort = ThinkingMapper.apply(level, config.newapiDialect ? "newapi" : "standard");
                if (effort != null) {
                    if ("max".equals(effort) && maxUnsupportedModels.contains(model.id)) {
                        effort = "high"; // 本会话内已知该模型不支持 max，直接降级
                    }
                    payload.addProperty("reasoning_effort", effort);
                }
            }

            // 步骤6：第一版不发送 max_tokens / max_completion_tokens，使用服务端默认额度
            // 推理模型不接受 temperature（A4）
            if (!model.isReasoningOnly) {
                payload.addProperty("temperature", 0.7);
            }

            // 工具：仅当前模型支持，且当前消息不含图片（numAi 经验；第一版默认不开启）
            boolean hasImages = currentUserMessage != null
                    && currentUserMessage.inputImages != null && !currentUserMessage.inputImages.isEmpty();
            if (model.supportsTools && !hasImages && (config.webSearchEnabled || config.webFetchEnabled)) {
                JsonArray tools = buildTools(config);
                if (tools.size() > 0) {
                    payload.add("tools", tools);
                    payload.addProperty("tool_choice", "auto");
                }
            }

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();

            OkHttpClient client = sseClient.newBuilder()
                    .readTimeout(300, TimeUnit.SECONDS)
                    .build();

            activeCall = client.newCall(request);
            try (Response response = activeCall.execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    String errLower = errBody.toLowerCase(Locale.ROOT);
                    if (response.code() == 400) {
                        // A1 400 自愈：含 reasoning_content 错误则翻转开关重试一次
                        if (!cancelled && reasoningRetries == 0 && errLower.contains("reasoning_content")) {
                            model.keepReasoningInHistory = !model.keepReasoningInHistory;
                            doChatCompletion(chat, currentUserMessage, model, level, assistantMsg, callback,
                                    reasoningRetries + 1, maxRetryUsed);
                            return;
                        }
                        // 步骤5：中转不支持 max 时降级为 high 重试一次，并记住该模型
                        if (!cancelled && !maxRetryUsed && "max".equals(effort)
                                && (errLower.contains("reasoning_effort") || errLower.contains("max"))) {
                            maxUnsupportedModels.add(model.id);
                            doChatCompletion(chat, currentUserMessage, model, level, assistantMsg, callback,
                                    reasoningRetries, true);
                            return;
                        }
                    }
                    throw new ApiError(response.code() + " " + errBody, false, errBody);
                }

                ResponseBody bodyStream = response.body();
                if (bodyStream == null) {
                    throw new ApiError("Empty response body");
                }

                ChatCompletionResult result = new ChatCompletionResult(assistantMsg);
                result.trimmed = trimmed;
                if (maxRetryUsed) {
                    result.notice = "中转站不支持 max 等级，已降级为 high";
                }
                parseStream(bodyStream.source(), assistantMsg, model, result);

                if (result.interrupted) {
                    if (isEmpty(assistantMsg.content, assistantMsg.reasoningRaw)) {
                        throw new ApiError("生成中断：连接提前关闭");
                    }
                    assistantMsg.setAsError(); // B4：保留已收到部分并标记中断
                }
                postSuccess(callback, result);
            } finally {
                activeCall = null;
            }

        } catch (ApiError e) {
            if (cancelled) {
                handleCancelled(assistantMsg, callback);
                return;
            }
            // B4 断流：保留已收到的部分内容并标记错误
            if (assistantMsg != null && !isEmpty(assistantMsg.content, assistantMsg.reasoningRaw)) {
                assistantMsg.setAsError();
                postSuccess(callback, new ChatCompletionResult(assistantMsg));
            } else {
                postError(callback, e);
            }
        } catch (Exception e) {
            if (cancelled) {
                handleCancelled(assistantMsg, callback);
                return;
            }
            if (assistantMsg != null && !isEmpty(assistantMsg.content, assistantMsg.reasoningRaw)) {
                assistantMsg.setAsError();
                postSuccess(callback, new ChatCompletionResult(assistantMsg));
            } else {
                postError(callback, new ApiError(e.getMessage()));
            }
        }
    }

    private boolean isEmpty(String... texts) {
        for (String t : texts) {
            if (t != null && t.length() > 0) return false;
        }
        return true;
    }

    private boolean containsSystemMessage(List<Message> messages) {
        for (Message m : messages) {
            if (m.getRoleEnum() == Role.SYSTEM) return true;
        }
        return false;
    }

    /** 取消后的统一收尾（步骤12）：标记中断、保留已收部分，仍走成功回调结束回合 */
    private void handleCancelled(Message assistantMsg, ApiCallback<ChatCompletionResult> callback) {
        if (assistantMsg != null) {
            assistantMsg.setAsError();
        }
        ChatCompletionResult result = new ChatCompletionResult(assistantMsg);
        result.interrupted = true;
        postSuccess(callback, result);
    }

    private JsonArray buildMessages(ModelDef model, List<Message> allMessages, Message currentUserMessage) {
        JsonArray messages = new JsonArray();

        for (Message m : allMessages) {
            JsonObject messageJson = new JsonObject();
            messageJson.addProperty("role", m.role);

            if (m.getRoleEnum() == Role.SYSTEM) {
                // system 永远纯字符串（C 类）
                messageJson.addProperty("content", m.content != null ? m.content : "");
                messages.add(messageJson);
                continue;
            }

            if (m.getRoleEnum() == Role.ASSISTANT) {
                messageJson.addProperty("content", m.content != null ? m.content : "");
                // 工具调用仅保留有对应 tool 回应的（numAi 逻辑）
                if (m.toolCalls != null && m.toolCalls.size() > 0 && hasMatchingToolResponses(m, allMessages)) {
                    messageJson.add("tool_calls", m.toolCalls);
                }
                // 步骤7：第一版不携带 tools 参数，历史 assistant 无需回传 reasoning_content
                // （工具调用阶段再启用；ModelDef.keepReasoningInHistory 与 400 自愈逻辑已保留）
                messages.add(messageJson);
                continue;
            }

            if (m.getRoleEnum() == Role.TOOL) {
                messageJson.addProperty("role", "tool");
                messageJson.addProperty("tool_call_id", m.toolCallId != null ? m.toolCallId : ""); // A2
                messageJson.addProperty("content", m.content != null ? m.content : "");
                messages.add(messageJson);
                continue;
            }

            // USER
            boolean isCurrent = (m == currentUserMessage);
            boolean hasImages = m.inputImages != null && !m.inputImages.isEmpty();
            if (hasImages && isCurrent) {
                JsonArray content = new JsonArray();
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", m.content != null ? m.content : "");
                content.add(textPart);

                if (isCurrent) {
                    for (String imageName : m.inputImages) {
                        String base64 = loadImageBase64(imageName);
                        if (base64 != null) {
                            JsonObject imagePart = new JsonObject();
                            imagePart.addProperty("type", "image_url");
                            JsonObject imageUrl = new JsonObject();
                            imageUrl.addProperty("url", "data:image/jpeg;base64," + base64);
                            imagePart.add("image_url", imageUrl);
                            content.add(imagePart);
                        }
                    }
                }
                messageJson.add("content", content);
            } else if (hasImages) {
                // 历史图片占位（A6）
                JsonArray content = new JsonArray();
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", (m.content != null ? m.content : "") + " [图片]");
                content.add(textPart);
                messageJson.add("content", content);
            } else {
                messageJson.addProperty("content", m.content != null ? m.content : "");
            }

            messages.add(messageJson);
        }

        return messages;
    }

    private boolean hasMatchingToolResponses(Message assistant, List<Message> all) {
        if (assistant.toolCalls == null) return false;
        for (int i = 0; i < assistant.toolCalls.size(); i++) {
            JsonObject tc = assistant.toolCalls.get(i).getAsJsonObject();
            String id = tc.has("id") ? tc.get("id").getAsString() : null;
            if (id == null) continue;
            boolean found = false;
            for (Message tm : all) {
                if (tm.getRoleEnum() == Role.TOOL && id.equals(tm.toolCallId)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private String loadImageBase64(String imageName) {
        File file = new File(context.getFilesDir(), "images" + File.separator + imageName);
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            if (read > 0) {
                return Base64.encodeToString(data, Base64.NO_WRAP); // C 类：NO_WRAP
            }
        } catch (IOException e) {
            Log.e(TAG, "load image failed: " + imageName, e);
        }
        return null;
    }

    private JsonArray buildTools(Config config) {
        JsonArray tools = new JsonArray();
        if (config.webSearchEnabled) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", "web_search");
            function.addProperty("description", "Search the web for real-time facts.");
            JsonObject params = new JsonObject();
            params.addProperty("type", "object");
            JsonObject props = new JsonObject();
            JsonObject q = new JsonObject();
            q.addProperty("type", "string");
            q.addProperty("description", "Search query");
            props.add("query", q);
            params.add("properties", props);
            JsonArray required = new JsonArray();
            required.add("query");
            params.add("required", required);
            function.add("parameters", params);
            tool.add("function", function);
            tools.add(tool);
        }
        if (config.webFetchEnabled) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", "web_fetch");
            function.addProperty("description", "Fetch a web page and return its content as Markdown.");
            JsonObject params = new JsonObject();
            params.addProperty("type", "object");
            JsonObject props = new JsonObject();
            JsonObject url = new JsonObject();
            url.addProperty("type", "string");
            url.addProperty("description", "URL to fetch");
            props.add("url", url);
            params.add("properties", props);
            JsonArray required = new JsonArray();
            required.add("url");
            params.add("required", required);
            function.add("parameters", params);
            tool.add("function", function);
            tools.add(tool);
        }
        return tools;
    }

    /** 提取流式 chunk 中的 error 对象信息（步骤4） */
    private String extractChunkError(JsonObject chunk) {
        JsonElement err = chunk.get("error");
        if (err.isJsonObject() && err.getAsJsonObject().has("message")
                && !err.getAsJsonObject().get("message").isJsonNull()) {
            return err.getAsJsonObject().get("message").getAsString();
        }
        if (err.isJsonPrimitive()) {
            return err.getAsString();
        }
        return err.toString();
    }

    /** SSE 流式解析（步骤4：处理 [DONE] / finish_reason / 流中 error 对象，标记截断与中断） */
    private void parseStream(okio.BufferedSource source, Message assistantMsg, ModelDef model,
                             ChatCompletionResult result) throws ApiError, IOException {
        String line;
        boolean receivedDone = false;
        String lastFinishReason = null;

        while ((line = source.readUtf8Line()) != null) {
            if (!line.startsWith("data:")) continue;
            String jsonData = line.substring(5).trim();
            if ("[DONE]".equals(jsonData)) {
                receivedDone = true;
                break;
            }

            JsonObject chunk;
            try {
                chunk = JsonParser.parseString(jsonData).getAsJsonObject();
            } catch (Exception e) {
                Log.e(TAG, "parse stream chunk", e);
                continue;
            }

            // 流中错误对象（步骤4）：{"error":{"message":...}}
            if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                throw new ApiError(extractChunkError(chunk), false, chunk.toString());
            }

            if (!chunk.has("choices")) continue;
            JsonArray choices = chunk.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) continue;

            try {
                JsonObject choice = choices.get(0).getAsJsonObject();

                JsonElement finishReason = choice.get("finish_reason");
                if (finishReason != null && !finishReason.isJsonNull()) {
                    lastFinishReason = finishReason.getAsString();
                }

                JsonElement deltaEl = choice.get("delta");
                if (deltaEl == null || deltaEl.isJsonNull() || !deltaEl.isJsonObject()) {
                    continue;
                }
                JsonObject delta = deltaEl.getAsJsonObject();

                if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                    assistantMsg.appendReasoning(delta.get("reasoning_content").getAsString());
                }
                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                    assistantMsg.appendContent(delta.get("content").getAsString());
                }

                // 工具调用流式聚合
                if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                    JsonArray tcArr = delta.getAsJsonArray("tool_calls");
                    for (int i = 0; i < tcArr.size(); i++) {
                        JsonObject tcObj = tcArr.get(i).getAsJsonObject();
                        String id = tcObj.has("id") && !tcObj.get("id").isJsonNull() ? tcObj.get("id").getAsString() : null;
                        int index = tcObj.has("index") ? tcObj.get("index").getAsInt() : 0;
                        Message.StreamToolCall stc = findOrCreateToolCall(assistantMsg, id, index);
                        if (id != null) stc.id = id;
                        if (tcObj.has("function")) {
                            JsonObject fn = tcObj.getAsJsonObject("function");
                            if (fn.has("name") && !fn.get("name").isJsonNull()) {
                                stc.name = fn.get("name").getAsString();
                            }
                            if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                                stc.arguments.append(fn.get("arguments").getAsString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "parse stream chunk", e);
            }
        }

        // 流结束，整理工具调用
        if (assistantMsg.streamingToolCalls != null && !assistantMsg.streamingToolCalls.isEmpty()) {
            JsonArray toolCalls = new JsonArray();
            for (Message.StreamToolCall stc : assistantMsg.streamingToolCalls) {
                JsonObject tc = new JsonObject();
                tc.addProperty("id", stc.id);
                tc.addProperty("type", "function");
                JsonObject fn = new JsonObject();
                fn.addProperty("name", stc.name);
                fn.addProperty("arguments", stc.arguments.toString());
                tc.add("function", fn);
                toolCalls.add(tc);
            }
            assistantMsg.toolCalls = toolCalls;
        }

        // 流结束判定（步骤4）：收到 [DONE] 或服务器给出终止 finish_reason 视为正常完成；
        // 两者都没有则视为连接中断（B4）
        if (receivedDone || lastFinishReason != null) {
            result.finished = true;
        } else {
            result.interrupted = true;
        }
        if ("length".equals(lastFinishReason)) result.truncated = true;
        if ("content_filter".equals(lastFinishReason)) result.filtered = true;
    }

    private Message.StreamToolCall findOrCreateToolCall(Message msg, String id, int index) {
        if (msg.streamingToolCalls == null) msg.streamingToolCalls = new ArrayList<>();
        if (id != null && id.length() > 0) {
            for (Message.StreamToolCall stc : msg.streamingToolCalls) {
                if (id.equals(stc.id)) return stc;
            }
        }
        if (index < msg.streamingToolCalls.size()) {
            Message.StreamToolCall stc = msg.streamingToolCalls.get(index);
            if (stc.id == null || stc.id.length() == 0 || (id != null && stc.id.equals(id))) return stc;
        }
        Message.StreamToolCall stc = new Message.StreamToolCall();
        stc.id = id;
        stc.index = index;
        msg.streamingToolCalls.add(stc);
        return stc;
    }

    /** 拉取指定供应商的模型列表（只解析、不落库，由调用方决定添加哪些） */
    public void getModels(Provider provider, ApiCallback<List<ModelDef>> callback) {
        if (provider == null) {
            postError(callback, new ApiError("未指定供应商"));
            return;
        }
        final String url = provider.normalizedBaseUrl() + "/models";
        new Thread(() -> {
            try {
                ApiRequest request = new ApiRequest("/models", "GET")
                        .setCredentials(provider.normalizedBaseUrl(), provider.apiKey);
                ApiResponse response = apiClient.execute(request);
                if (!response.successful) {
                    postError(callback, new ApiError(response.errorBody));
                    return;
                }

                // 宽松解析：部分站点返回的 JSON 并不规范（未加引号的键等），严格模式会直接抛
                // "Use JsonReader.setLenient(true)..."；真的不是 JSON（如 HTML 错误页）时给出可定位提示
                JsonElement root;
                try {
                    root = JsonUtil.parseLenient(response.body);
                } catch (Exception parseFailure) {
                    postError(callback, new ApiError(
                            describeBadModelsResponse(url, response, "返回内容不是有效的 JSON")));
                    return;
                }
                if (root == null || !root.isJsonObject()) {
                    postError(callback, new ApiError(
                            describeBadModelsResponse(url, response, "响应不是 JSON 对象")));
                    return;
                }
                JsonArray data = root.getAsJsonObject().getAsJsonArray("data");
                if (data == null) {
                    postError(callback, new ApiError(describeBadModelsResponse(url, response,
                            "响应里没有 data 数组（该站可能不支持 /models）")));
                    return;
                }
                List<ModelDef> models = ModelCatalog.getInstance().parseModelsEndpoint(data, provider.id);
                postSuccess(callback, models);
            } catch (ApiError e) {
                postError(callback, e);
            } catch (Exception e) {
                postError(callback, new ApiError(e.getMessage()));
            }
        }).start();
    }

    /** 拉取模型失败时拼装可读原因：请求地址 + HTTP 状态 + 实际返回片段（疑似非 JSON 时附检查建议） */
    private String describeBadModelsResponse(String url, ApiResponse response, String reason) {
        StringBuilder sb = new StringBuilder(reason);
        sb.append("；请求 ").append(url)
                .append(" 返回（HTTP ").append(response.statusCode).append("）：")
                .append(JsonUtil.snippet(response.body));
        if (!JsonUtil.looksLikeJson(response.body)) {
            sb.append("。请检查中转地址是否以 /v1 结尾、该站是否提供 /models 接口");
        }
        return sb.toString();
    }

    private String trimSlash(String url) {
        if (url == null) return "";
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private <T> void postSuccess(final ApiCallback<T> callback, final T result) {
        mainHandler.post(() -> callback.onSuccess(result));
    }

    private <T> void postError(final ApiCallback<T> callback, final ApiError error) {
        mainHandler.post(() -> callback.onError(error));
    }

    /** chatCompletion 结果：包含 assistant 消息与流结束标记 */
    public static class ChatCompletionResult {
        public final Message assistantMessage;
        public final List<Message.StreamToolCall> toolCalls = new ArrayList<>();
        public boolean finished = false;    // 流正常结束（[DONE] 或收到 finish_reason）
        public boolean trimmed = false;     // 上下文发生裁剪（步骤17：UI 轻提示）
        public boolean truncated = false;   // finish_reason=length，因长度截断（步骤4）
        public boolean filtered = false;    // finish_reason=content_filter，内容被过滤（步骤4）
        public boolean interrupted = false; // 流中断：未收到 [DONE] 且无 finish_reason（步骤4）
        public String notice;               // 一次性提示（如 MAX 自动降级，步骤5）

        public ChatCompletionResult(Message assistantMessage) {
            this.assistantMessage = assistantMessage;
        }
    }
}
