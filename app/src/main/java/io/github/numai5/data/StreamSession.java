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
    /** 回合号：新回合/取消时递增，旧回合的回调据此丢弃，避免旧请求复活新一轮界面 */
    private long roundSeq;
    private long activeRound;

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
        final long round = ++roundSeq;
        activeRound = round;
        apiService.chatCompletion(chat, userMessage, model, level, assistantMsg,
                new ApiCallback<ApiService.ChatCompletionResult>() {
                    @Override
                    public void onSuccess(ApiService.ChatCompletionResult result) {
                        if (round != activeRound) return; // 旧回合：已被新回合或取消取代
                        finish(chat, assistantMsg, result, null);
                    }

                    @Override
                    public void onError(ApiError error) {
                        if (round != activeRound) return;
                        finish(chat, assistantMsg, null, error);
                    }
                });
    }

    /** 取消进行中的请求（步骤12）；结束后仍会走 finish() 流程 */
    public void cancel() {
        // 不递增回合号：本轮仍需正常走到 finish()，把已收内容标记为中断；
        // 旧线程由 ApiService 自己的取消令牌与轮次号拦下，不会写入下一轮界面。
        apiService.cancel();
    }

    /** 当前排队名次：1 表示下一个；0 表示未排队 / 正在发送 */
    public int queuePosition() {
        return apiService.getChatQueuePosition();
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
