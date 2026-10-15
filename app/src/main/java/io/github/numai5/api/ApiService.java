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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import io.github.numai5.thinking.ThinkingMapper;
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

    private final Context context;
    private final ApiClient apiClient;
    private final ConfigManager configManager;
    private final Handler mainHandler;
    private final OkHttpClient sseClient;

    public ApiService(Context context) {
        this.context = context.getApplicationContext();
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

    public void chatCompletion(Chat chat, Message currentUserMessage, ModelDef model, ThinkingLevel level,
                               ApiCallback<ChatCompletionResult> callback) {
        new Thread(() -> doChatCompletion(chat, currentUserMessage, model, level, callback, 0)).start();
    }

    private void doChatCompletion(Chat chat, Message currentUserMessage, ModelDef model, ThinkingLevel level,
                                  ApiCallback<ChatCompletionResult> callback, int retryCount) {
        Config config = configManager.getConfig();
        String baseUrl = trimSlash(config.baseUrl);
        String modelId = model.id;
        Message assistantMsg = null;

        try {
            List<Message> history = new ArrayList<>();
            boolean trimmed = ContextTrimmer.trim(chat.messages, history);
            // TODO: UI 提示 trimmed

            JsonObject body = buildRequestBody(model, level, history, currentUserMessage);
            JsonArray messages = buildMessages(model, history, currentUserMessage);
            body.add("messages", messages);

            JsonObject payload = new JsonObject();
            payload.addProperty("model", modelId);
            payload.add("messages", messages);
            payload.addProperty("stream", true);

            // 思考等级（A3）
            if (model.supportsThinking) {
                String effort = ThinkingMapper.apply(level, model.effortDialect);
                if (effort != null) {
                    payload.addProperty("reasoning_effort", effort);
                }
            }

            // max_tokens / max_completion_tokens 门控（A4）
            if (model.needsMaxCompletionTokens) {
                payload.addProperty("max_completion_tokens", 4096);
            } else {
                payload.addProperty("max_tokens", 4096);
            }

            // 推理模型不接受 temperature（A4）
            if (!model.isReasoningOnly) {
                payload.addProperty("temperature", 0.7);
            }

            // 工具：仅当前模型支持，且当前消息不含图片（numAi 经验）
            boolean hasImages = currentUserMessage.inputImages != null && !currentUserMessage.inputImages.isEmpty();
            if (model.supportsTools && !hasImages && (config.webSearchEnabled || config.webFetchEnabled)) {
                JsonArray tools = buildTools(config);
                if (tools.size() > 0) {
                    payload.add("tools", tools);
                    payload.addProperty("tool_choice", "auto");
                }
            }

            assistantMsg = MessageManager.createAssistantMessage(modelId, model.providerId, level.name());

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + config.apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();

            OkHttpClient client = sseClient.newBuilder()
                    .readTimeout(300, TimeUnit.SECONDS)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    // A1 400 自愈：含 reasoning_content 错误则翻转开关重试一次
                    if (response.code() == 400 && retryCount == 0
                            && errBody != null && errBody.contains("reasoning_content")) {
                        model.keepReasoningInHistory = !model.keepReasoningInHistory;
                        doChatCompletion(chat, currentUserMessage, model, level, callback, retryCount + 1);
                        return;
                    }
                    throw new ApiError(response.code() + " " + errBody, false, errBody);
                }

                ResponseBody bodyStream = response.body();
                if (bodyStream == null) {
                    throw new ApiError("Empty response body");
                }

                ChatCompletionResult result = new ChatCompletionResult(assistantMsg);
                parseStream(bodyStream.source(), assistantMsg, model, result);
                callback.onSuccess(result);
            }

        } catch (ApiError e) {
            // B4 断流：保留已收到的部分内容并标记错误
            if (assistantMsg != null && !isEmpty(assistantMsg.content, assistantMsg.reasoningRaw)) {
                assistantMsg.setAsError();
                postSuccess(callback, new ChatCompletionResult(assistantMsg));
            } else {
                postError(callback, e);
            }
        } catch (Exception e) {
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

    private JsonObject buildRequestBody(ModelDef model, ThinkingLevel level, List<Message> history,
                                        Message currentUserMessage) {
        return new JsonObject();
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
                // reasoning_content 按模型开关（A1）
                if (model.keepReasoningInHistory && m.reasoningRaw != null && m.reasoningRaw.length() > 0) {
                    messageJson.addProperty("reasoning_content", m.reasoningRaw);
                }
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

    /** SSE 流式解析 */
    private void parseStream(okio.BufferedSource source, Message assistantMsg, ModelDef model,
                             ChatCompletionResult result) throws ApiError, IOException {
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (!line.startsWith("data:")) continue;
            String jsonData = line.substring(5).trim();
            if ("[DONE]".equals(jsonData)) break;
            try {
                JsonObject chunk = JsonParser.parseString(jsonData).getAsJsonObject();
                if (!chunk.has("choices")) continue;
                JsonArray choices = chunk.getAsJsonArray("choices");
                if (choices == null || choices.size() == 0) continue;
                JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                if (delta == null) continue;

                if (delta.has("reasoning_content")) {
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

        result.finished = true;
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

    public void getModels(ApiCallback<List<ModelDef>> callback) {
        new Thread(() -> {
            Config config = configManager.getConfig();
            try {
                ApiRequest request = new ApiRequest("/models", "GET")
                        .setCredentials(trimSlash(config.baseUrl), config.apiKey);
                ApiResponse response = apiClient.execute(request);
                if (!response.successful) {
                    postError(callback, new ApiError(response.errorBody));
                    return;
                }
                JsonObject obj = JsonParser.parseString(response.body).getAsJsonObject();
                JsonArray data = obj.getAsJsonArray("data");
                ModelCatalog.getInstance().mergeFromModelsEndpoint(data);
                postSuccess(callback, ModelCatalog.getInstance().getAllModels());
            } catch (ApiError e) {
                postError(callback, e);
            } catch (Exception e) {
                postError(callback, new ApiError(e.getMessage()));
            }
        }).start();
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

    /** chatCompletion 结果：包含 assistant 消息和工具调用列表 */
    public static class ChatCompletionResult {
        public final Message assistantMessage;
        public final List<Message.StreamToolCall> toolCalls = new ArrayList<>();
        public boolean finished = false;

        public ChatCompletionResult(Message assistantMessage) {
            this.assistantMessage = assistantMessage;
        }
    }
}
