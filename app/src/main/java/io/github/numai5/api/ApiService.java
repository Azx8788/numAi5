package io.github.numai5.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.concurrent.atomic.AtomicLong;
import io.github.numai5.data.ConfigManager;
import io.github.numai5.data.ContextTrimmer;
import io.github.numai5.data.MemoryStore;
import io.github.numai5.data.MessageManager;
import io.github.numai5.data.ProviderRateLimiter;
import io.github.numai5.model.Chat;
import io.github.numai5.model.Config;
import io.github.numai5.model.Message;
import io.github.numai5.model.Role;
import io.github.numai5.model.ThinkingLevel;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;
import io.github.numai5.api.protocol.ChunkDelta;
import io.github.numai5.api.protocol.Protocols;
import io.github.numai5.api.protocol.ProviderProtocol;
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
 *
 * 请求调度与取消（v1.4）：
 * - 每轮对话有自己的取消令牌（CancelToken），不再用会被新请求重置的共享布尔量；
 *   旧线程因令牌或轮次号过期而退出，不会写入新一轮界面状态。
 * - 每次实际 HTTP 请求都经 ProviderRateLimiter（供应商级并发/间隔/RPM/冷却）。
 * - 自动重试为 0：429、5xx 一律不重发；400 参数自愈与 max 降级受统一请求预算约束，
 *   不再用递归调用各自重置计数器。
 */
public class ApiService {
    private static final String TAG = "ApiService";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * 单次用户操作的 HTTP 请求预算：首次请求 + 一次参数自愈 + 一次 max 降级。
     * 递归已改为迭代，预算在循环里统一扣减，任何分支都不能突破。
     */
    private static final int CHAT_REQUEST_BUDGET = 3;

    /** 本会话内已确认不支持 max 的模型 id（步骤5）：之后 MAX 直接降级 high，不再发送 max */
    private static final Set<String> maxUnsupportedModels =
            Collections.synchronizedSet(new HashSet<>());

    private final Context context;
    private final ApiClient apiClient;
    private final ConfigManager configManager;
    private final Handler mainHandler;
    private final OkHttpClient sseClient;

    /** 当前轮次的取消令牌（每轮独立；取消即断开对应 OkHttp Call） */
    private volatile CancelToken activeToken;
    /** 当前轮次号：新轮次/取消时递增，旧线程据此判定自己已过期 */
    private final AtomicLong generationSeq = new AtomicLong(0);
    private volatile long activeGeneration = 0;
    /** 当前聊天请求的调度条目（排队名次与取消用） */
    private volatile ProviderRateLimiter.Entry chatEntry;
    /** 当前聊天请求所属供应商（取消时只清该供应商的聊天队列） */
    private volatile Provider activeProvider;

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

    /** 一轮对话的独立取消令牌；每轮新建，互不影响 */
    public static final class CancelToken {
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * 发起一轮对话（步骤2：assistantMsg 由调用方创建并加入会话，便于 UI 轮询流式刷新）。
     * 回调统一切到主线程；中断时保留已收到的部分内容并标记 isError（B4）。
     */
    public void chatCompletion(Chat chat, Message currentUserMessage, ModelDef model, ThinkingLevel level,
                               Message assistantMsg, ApiCallback<ChatCompletionResult> callback) {
        long generation = generationSeq.incrementAndGet();
        activeGeneration = generation;
        CancelToken token = new CancelToken();
        activeToken = token;
        new Thread(() -> doChatCompletion(generation, token, chat, currentUserMessage, model, level,
                assistantMsg, callback)).start();
    }

    /** 当前聊天请求的排队名次：1 表示下一个；0 表示未排队（执行中或已取消） */
    public int getChatQueuePosition() {
        return ProviderRateLimiter.positionOf(chatEntry);
    }

    /**
     * 取消进行中的请求（步骤12）：当前调用立即断开，回合以「中断」结束并保留已收内容。
     * 只影响聊天：清掉该供应商的聊天排队，生图/模型列表任务不受影响。
     */
    public void cancel() {
        long generation = generationSeq.incrementAndGet();
        activeGeneration = generation; // 让所有在途线程判定自己已过期
        CancelToken token = activeToken;
        if (token != null) {
            token.cancel();
        }
        Provider provider = activeProvider;
        if (provider != null) {
            ProviderRateLimiter.cancelCategory(provider, ProviderRateLimiter.RequestType.CHAT);
        }
        ProviderRateLimiter.Entry entry = chatEntry;
        if (entry != null) {
            ProviderRateLimiter.cancel(entry); // 已绑定 Call 的会立即 cancel
        }
    }

    /** 取消该供应商正在等待的模型列表请求（设置页离开时调用），不影响聊天与生图 */
    public void cancelModels(Provider provider) {
        if (provider == null) return;
        ProviderRateLimiter.cancelCategory(provider, ProviderRateLimiter.RequestType.MODEL_LIST);
    }

    /** 该线程是否仍然有效：未被取消，且没有被更新的一轮取代 */
    private boolean isActive(long generation, CancelToken token) {
        return token != null && !token.isCancelled() && generation == activeGeneration;
    }

    private void doChatCompletion(long generation, CancelToken token, Chat chat, Message currentUserMessage,
                                  ModelDef model, ThinkingLevel level, Message assistantMsg,
                                  ApiCallback<ChatCompletionResult> callback) {
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
        activeProvider = provider;
        ProviderProtocol protocol = Protocols.of(provider, apiClient); // 发送/接收协议差异都从这里走
        String modelId = model.requestId(); // 步骤8：优先使用 /models 返回的原始 id

        if (!isActive(generation, token)) {
            handleCancelled(assistantMsg, callback);
            return;
        }

        // 聊天历史 + 记忆注入只构建一次；每次 HTTP 请求只是重新组装 payload
        List<Message> history;
        boolean trimmed;
        String effort;
        try {
            // 排除本轮占位的 assistant 消息，避免把空回复发进请求历史
            List<Message> source = new ArrayList<>();
            for (Message m : chat.messages) {
                if (m != assistantMsg) source.add(m);
            }
            // 本地记忆：按当前用户消息匹配，只在本次请求注入，不改动聊天历史。
            MemoryStore.Injection memory = config.memoryEnabled
                    ? MemoryStore.getInstance(context).buildInjection(
                            currentUserMessage != null ? currentUserMessage.content : "")
                    : null;
            String memoryText = memory != null && !memory.isEmpty() ? memory.text : "";

            // 记忆块占用上下文预算：从历史字符预算中扣除，避免固定注入把历史挤爆。
            String sysPrompt = config.systemPrompt != null ? config.systemPrompt.trim() : "";
            int reserve = sysPrompt.length() + memoryText.length() + 200;
            int maxChars = Math.max(4000, ContextTrimmer.DEFAULT_MAX_CHARS - reserve);
            history = new ArrayList<>();
            trimmed = ContextTrimmer.trim(source, history,
                    ContextTrimmer.DEFAULT_MAX_ROUNDS, maxChars);
            if (currentUserMessage != null && !history.contains(currentUserMessage)) {
                history.add(currentUserMessage); // 容错：调用方未把当前消息并入历史时兜底
            }
            // 系统提示词与记忆合并为一条 system；已有 system 消息时记忆单独一条，避免覆盖用户内容。
            if (!containsSystemMessage(history)) {
                String combined = sysPrompt;
                if (memoryText.length() > 0) {
                    combined = combined.length() == 0 ? memoryText : combined + "\n\n" + memoryText;
                }
                if (combined.length() > 0) {
                    history.add(0, MessageManager.createSystemMessage(combined));
                }
            } else if (memoryText.length() > 0) {
                history.add(0, MessageManager.createSystemMessage(memoryText));
            }
            effort = resolveEffort(model, level, config);
        } catch (Exception e) {
            postError(callback, new ApiError(e.getMessage()));
            return;
        }

        int sent = 0;                  // 已发送的 HTTP 请求数（统一预算）
        boolean selfHealed = false;    // reasoning_content 参数自愈是否已用
        boolean maxDowngraded = false; // max 降级是否已用
        String notice = null;
        String chatUrl = protocol.chatUrl(provider);
        OkHttpClient client = sseClient.newBuilder()
                .readTimeout(300, TimeUnit.SECONDS)
                .build();

        while (true) {
            if (!isActive(generation, token)) {
                handleCancelled(assistantMsg, callback);
                return;
            }
            if (sent >= CHAT_REQUEST_BUDGET) {
                postError(callback, new ApiError("本次请求已达到发送上限（" + CHAT_REQUEST_BUDGET
                        + " 次），请稍后重试或更换模型"));
                return;
            }
            sent++;

            Request request;
            try {
                JsonObject payload = buildPayload(model, history, currentUserMessage, config, effort, modelId);
                Request.Builder requestBuilder = new Request.Builder().url(chatUrl);
                protocol.applyHeaders(requestBuilder, provider, chatUrl);
                request = requestBuilder
                        .post(RequestBody.create(payload.toString(), JSON))
                        .build();
            } catch (Exception e) {
                postError(callback, new ApiError(e.getMessage()));
                return;
            }

            ProviderRateLimiter.Entry entry = ProviderRateLimiter.submit(
                    provider, ProviderRateLimiter.RequestType.CHAT);
            chatEntry = entry;
            ProviderRateLimiter.Denial denial = ProviderRateLimiter.awaitTurn(entry);
            if (denial != null) {
                postError(callback, RateLimitPolicy.denialError(denial));
                return;
            }
            // 获准后、发送前：被取消则不发送，并按终态释放并发许可
            if (!isActive(generation, token) || !ProviderRateLimiter.markRunning(entry)) {
                ProviderRateLimiter.finish(entry, ProviderRateLimiter.State.CANCELLED, null);
                handleCancelled(assistantMsg, callback);
                return;
            }

            Call call = client.newCall(request);
            ProviderRateLimiter.bind(entry, call::cancel);
            ProviderRateLimiter.State terminal = ProviderRateLimiter.State.FAILED;
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    String errLower = errBody.toLowerCase(Locale.ROOT);
                    ApiError mapped = ErrorMapper.fromHttp(response.code(), errBody,
                            response.header("Retry-After"));
                    // 先按类别更新供应商调度状态：429 冷却、额度/认证暂停，均不重发本请求
                    RateLimitPolicy.report(provider, mapped);

                    if (response.code() == 400 && !selfHealed && sent < CHAT_REQUEST_BUDGET
                            && errLower.contains("reasoning_content")) {
                        // A1 400 自愈：含 reasoning_content 错误则翻转开关重发（占同一预算）
                        model.keepReasoningInHistory = !model.keepReasoningInHistory;
                        selfHealed = true;
                        continue;
                    }
                    if (response.code() == 400 && !maxDowngraded && "max".equals(effort)
                            && sent < CHAT_REQUEST_BUDGET
                            && (errLower.contains("reasoning_effort") || errLower.contains("max"))) {
                        // 步骤5：中转不支持 max 时降级为 high（占同一预算），并记住该模型
                        maxUnsupportedModels.add(model.id);
                        effort = "high";
                        maxDowngraded = true;
                        notice = "中转站不支持 max 等级，已降级为 high";
                        continue;
                    }
                    throw mapped;
                }

                ResponseBody bodyStream = response.body();
                if (bodyStream == null) {
                    throw new ApiError("Empty response body");
                }

                ChatCompletionResult result = new ChatCompletionResult(assistantMsg);
                result.trimmed = trimmed;
                if (notice != null) {
                    result.notice = notice;
                }
                parseStream(bodyStream.source(), assistantMsg, model, result, protocol);
                terminal = ProviderRateLimiter.State.COMPLETED;

                if (result.interrupted) {
                    if (isEmpty(assistantMsg.content, assistantMsg.reasoningRaw)) {
                        throw new ApiError("生成中断：连接提前关闭");
                    }
                    assistantMsg.setAsError(); // B4：保留已收到部分并标记中断
                }
                postSuccess(callback, result);
                return;
            } catch (ApiError e) {
                // 已知的 HTTP 错误（含 429/额度/认证）：不重发，直接结束本轮
                finishChatFailure(assistantMsg, callback, e, token, generation);
                return;
            } catch (IOException e) {
                if (!isActive(generation, token)) {
                    handleCancelled(assistantMsg, callback);
                    return;
                }
                boolean timeout = e instanceof java.net.SocketTimeoutException;
                finishChatFailure(assistantMsg, callback,
                        ErrorMapper.fromNetwork(e.getMessage(), timeout), token, generation);
                return;
            } finally {
                ProviderRateLimiter.finish(entry, terminal, null); // 幂等，确保并发许可只释放一次
                if (chatEntry == entry) chatEntry = null;
            }
        }
    }

    /** 依据模型与思考等级算出 reasoning_effort 的值（可能为 null） */
    private String resolveEffort(ModelDef model, ThinkingLevel level, Config config) {
        if (!model.isThinking()) return null;
        // 思考等级（步骤5）：方言开关从配置读取（默认 NewAPI 方言）
        String effort = ThinkingMapper.apply(level, config.newapiDialect ? "newapi" : "standard");
        if ("max".equals(effort) && maxUnsupportedModels.contains(model.id)) {
            return "high"; // 本会话内已知该模型不支持 max，直接降级
        }
        return effort;
    }

    /** 组装一次 Chat Completions 请求体（不含重试状态，便于循环内复用） */
    private JsonObject buildPayload(ModelDef model, List<Message> history, Message currentUserMessage,
                                    Config config, String effort, String modelId) {
        JsonArray messages = buildMessages(model, history, currentUserMessage);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelId);
        payload.add("messages", messages);
        payload.addProperty("stream", true);

        if (effort != null) {
            payload.addProperty("reasoning_effort", effort);
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
        return payload;
    }

    /** 本轮失败收尾：已有输出则保留内容并标记中断，否则抛出映射后的错误 */
    private void finishChatFailure(Message assistantMsg, ApiCallback<ChatCompletionResult> callback,
                                   ApiError error, CancelToken token, long generation) {
        if (!isActive(generation, token)) {
            handleCancelled(assistantMsg, callback);
            return;
        }
        if (assistantMsg != null && !isEmpty(assistantMsg.content, assistantMsg.reasoningRaw)) {
            assistantMsg.setAsError();
            postSuccess(callback, new ChatCompletionResult(assistantMsg));
        } else {
            postError(callback, error);
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

    /** SSE 流式解析（步骤4：处理 [DONE] / finish_reason / 流中 error 对象，标记截断与中断） */
    private void parseStream(okio.BufferedSource source, Message assistantMsg, ModelDef model,
                             ChatCompletionResult result, ProviderProtocol protocol)
            throws ApiError, IOException {
        String line;
        boolean receivedDone = false;
        String lastFinishReason = null;

        while ((line = source.readUtf8Line()) != null) {
            if (!line.startsWith("data:")) continue;
            String jsonData = line.substring(5).trim();
            // 部分站点会插入 data: null / 空行作为心跳，直接跳过
            if (jsonData.length() == 0 || "null".equals(jsonData)) continue;
            if ("[DONE]".equals(jsonData)) {
                receivedDone = true;
                break;
            }

            JsonObject chunk;
            try {
                JsonElement chunkElement = JsonUtil.parseLenient(jsonData);
                if (chunkElement == null || !chunkElement.isJsonObject()) continue;
                chunk = chunkElement.getAsJsonObject();
            } catch (Exception e) {
                Log.e(TAG, "parse stream chunk", e);
                continue;
            }

            // 流中错误对象（步骤4）：OpenAI 的 error / 魔搭的 errors，解码交给协议
            if ((chunk.has("error") && !chunk.get("error").isJsonNull())
                    || (chunk.has("errors") && !chunk.get("errors").isJsonNull())) {
                throw new ApiError(protocol.decodeChunkError(chunk), false, chunk.toString());
            }

            try {
                ChunkDelta delta = protocol.decodeChunk(chunk);
                if (delta.finishReason != null) lastFinishReason = delta.finishReason;
                if (delta.reasoning != null) assistantMsg.appendReasoning(delta.reasoning);
                if (delta.content != null) assistantMsg.appendContent(delta.content);

                // 工具调用流式聚合（增量交给协议解码，聚合逻辑不变）
                for (ChunkDelta.ToolCallDelta tcd : delta.toolCalls) {
                    Message.StreamToolCall stc = findOrCreateToolCall(assistantMsg, tcd.id, tcd.index);
                    if (tcd.id != null) stc.id = tcd.id;
                    if (tcd.name != null) stc.name = tcd.name;
                    if (tcd.arguments != null) stc.arguments.append(tcd.arguments);
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

    /** 拉取指定供应商的模型列表（协议差异交给适配器；只解析、不落库） */
    public void getModels(Provider provider, ApiCallback<List<ModelDef>> callback) {
        if (provider == null) {
            postError(callback, new ApiError("未指定供应商"));
            return;
        }
        new Thread(() -> {
            try {
                ProviderProtocol protocol = Protocols.of(provider, apiClient);
                postSuccess(callback, protocol.fetchModels(provider));
            } catch (ApiError e) {
                postError(callback, e);
            } catch (Exception e) {
                postError(callback, new ApiError(e.getMessage()));
            }
        }).start();
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
