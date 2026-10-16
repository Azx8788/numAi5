package io.github.numai5.data;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.github.numai5.ImageGenService;
import io.github.numai5.model.Chat;
import io.github.numai5.model.Message;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;

/**
 * 生图任务协调者（进程级单例 + 任务落盘）。
 *
 * 后台执行：任务交给 ImageGenService（前台服务）执行，切走 App 也会继续；
 * 任务信息持久化到 SharedPreferences，进程被杀后服务完成时仍能凭 chatId/messageId 找回消息写入结果。
 * 同一时间只允许一个生图任务，避免重复扣费。
 */
public class ImageGenManager {
    private static final String PREFS = "numai5_imagegen";
    private static final String KEY_TASK_ID = "taskId";
    private static final String KEY_CHAT_ID = "chatId";
    private static final String KEY_MESSAGE_ID = "messageId";
    private static final String KEY_PROMPT = "prompt";

    /** UI 监听（Activity 注册/注销） */
    public interface Listener {
        void onImageGenFinished(String messageId, boolean success, String detail);
    }

    private static ImageGenManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final List<Listener> listeners = new ArrayList<>();

    private String activeTaskId;
    private String activeMessageId;

    private ImageGenManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized ImageGenManager getInstance(Context context) {
        if (instance == null) {
            instance = new ImageGenManager(context);
        }
        return instance;
    }

    public static ImageGenManager getInstance() {
        return instance;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 进程内是否有生图任务在跑（UI 判断用；进程被杀后不会误报） */
    public boolean isRunning() {
        return activeTaskId != null;
    }

    /**
     * 是否存在上次进程被杀留下的未完成任务记录。
     * 生图服务与协调者同进程，进程没了服务也没了，所以这种残留一定是中断的旧任务。
     */
    public boolean hasStaleTask() {
        return activeTaskId == null && prefs.getString(KEY_TASK_ID, null) != null;
    }

    /** 丢弃残留的旧任务并把对应消息标记为中断，避免"生图中"状态永久卡住 */
    public void discardStaleTask() {
        String chatId = prefs.getString(KEY_CHAT_ID, null);
        String messageId = prefs.getString(KEY_MESSAGE_ID, null);
        prefs.edit().clear().apply();
        if (chatId == null || messageId == null) return;

        Chat chat = findChat(chatId);
        if (chat == null) return;
        for (Message m : chat.messages) {
            if (messageId.equals(m.id) && (m.generatedImages == null || m.generatedImages.isEmpty())) {
                m.content = "⚠ 生图中断（应用被系统回收）";
                m.setAsError();
                break;
            }
        }
        ChatManager.getInstance().onMessageAdded(chat);
    }

    public String getActiveMessageId() {
        return activeMessageId;
    }

    /**
     * 提交生图任务：创建「生成中」的 assistant 消息并落盘，然后启动前台服务执行。
     * @return 新建的消息 id（失败返回 null）
     */
    public String submit(Context context, Chat chat, Provider provider, ModelDef model,
                         String prompt, String size, int n) {
        if (chat == null || provider == null || model == null) return null;
        if (isRunning()) return null;
        if (hasStaleTask()) discardStaleTask(); // 清理上次进程被杀留下的旧任务

        Message msg = MessageManager.createAssistantMessage(model.id, provider.displayName(), null);
        msg.content = "🎨 正在生成图片：" + prompt;
        chat.messages.add(msg);
        ChatManager.getInstance().onMessageAdded(chat);

        String taskId = UUID.randomUUID().toString();
        activeTaskId = taskId;
        activeMessageId = msg.id;

        prefs.edit()
                .putString(KEY_TASK_ID, taskId)
                .putString(KEY_CHAT_ID, chat.id)
                .putString(KEY_MESSAGE_ID, msg.id)
                .putString(KEY_PROMPT, prompt)
                .apply();

        ImageGenService.start(appContext, provider.id, model.id, prompt, size, n);
        return msg.id;
    }

    /**
     * 服务完成回调。
     * 无论进程是否重启过，都通过 chatId/messageId 找回消息写入结果。
     */
    public void onFinished(List<String> filenames, String errorMessage) {
        String chatId = prefs.getString(KEY_CHAT_ID, null);
        String messageId = prefs.getString(KEY_MESSAGE_ID, null);
        String prompt = prefs.getString(KEY_PROMPT, "");
        prefs.edit().clear().apply();
        activeTaskId = null;
        activeMessageId = null;

        if (chatId != null && messageId != null) {
            Chat chat = findChat(chatId);
            if (chat != null) {
                Message target = null;
                for (Message m : chat.messages) {
                    if (messageId.equals(m.id)) {
                        target = m;
                        break;
                    }
                }
                if (target != null) {
                    if (errorMessage != null) {
                        target.content = "⚠ 生图失败：" + errorMessage;
                        target.setAsError();
                    } else {
                        target.generatedImages = filenames;
                        target.content = "🎨 " + prompt;
                    }
                }
                ChatManager.getInstance().onMessageAdded(chat);
            }
        }

        for (Listener l : new ArrayList<>(listeners)) {
            l.onImageGenFinished(messageId, errorMessage == null, errorMessage);
        }
    }

    private Chat findChat(String chatId) {
        for (Chat c : ChatManager.getInstance().getChats()) {
            if (chatId.equals(c.id)) return c;
        }
        return null;
    }
}
