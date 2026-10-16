package io.github.numai5.data;

import android.content.Context;
import io.github.numai5.api.ApiCallback;
import io.github.numai5.api.ApiError;
import io.github.numai5.api.ApiService;
import io.github.numai5.model.Chat;
import io.github.numai5.model.Message;
import io.github.numai5.model.ThinkingLevel;
import io.github.numai5.provider.ModelDef;

/**
 * 进行中的流式回合（步骤12）。
 * 进程级单例：旋转等 Activity 重建后保留执行状态，新 Activity 可恢复轮询并接收结束回调；
 * 进程被杀后单例随进程消失，不会伪装成仍在生成（isGenerating 不写入持久化）。
 */
public class StreamSession {

    /** Activity 级监听：用于停止轮询、提示等 UI 反应；为 null 时结束逻辑照常完成 */
    public interface Listener {
        void onStreamFinished(Message assistantMsg, ApiService.ChatCompletionResult result, ApiError error);
    }

    private static StreamSession instance;

    private final ApiService apiService;
    private Listener listener;
    private Chat activeChat;
    private Message activeMsg;
    private boolean generating;

    private StreamSession(Context context) {
        this.apiService = new ApiService(context.getApplicationContext());
    }

    public static synchronized StreamSession getInstance(Context context) {
        if (instance == null) {
            instance = new StreamSession(context);
        }
        return instance;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isGenerating() {
        return generating;
    }

    public Message getActiveMsg() {
        return activeMsg;
    }

    /** 发起一轮对话：assistantMsg 由调用方创建并加入会话（步骤2 契约） */
    public void start(Chat chat, Message userMessage, Message assistantMsg, ModelDef model, ThinkingLevel level) {
        this.activeChat = chat;
        this.activeMsg = assistantMsg;
        this.generating = true;
        apiService.chatCompletion(chat, userMessage, model, level, assistantMsg,
                new ApiCallback<ApiService.ChatCompletionResult>() {
                    @Override
                    public void onSuccess(ApiService.ChatCompletionResult result) {
                        finish(chat, assistantMsg, result, null);
                    }

                    @Override
                    public void onError(ApiError error) {
                        finish(chat, assistantMsg, null, error);
                    }
                });
    }

    /** 取消进行中的请求（步骤12）；结束后仍会走 finish() 流程 */
    public void cancel() {
        apiService.cancel();
    }

    /** 回合结束（主线程）：写回错误信息、保存历史，最后通知 UI 监听（若有） */
    private void finish(Chat chat, Message assistantMsg, ApiService.ChatCompletionResult result, ApiError error) {
        generating = false;
        if (error != null && assistantMsg != null) {
            String message = error.getMessage();
            assistantMsg.content = "⚠ " + (message != null ? message : "");
            assistantMsg.setAsError();
        }
        if (chat != null) {
            ChatManager.getInstance().onMessageAdded(chat);
        }
        if (listener != null) {
            listener.onStreamFinished(assistantMsg, result, error);
        }
    }
}
