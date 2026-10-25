package io.github.numai5.data;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.numai5.ImageGenService;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;

/**
 * 生图任务协调者（进程级单例 + 任务落盘）。
 *
 * 生图是独立流程，不写入聊天会话：
 * - 提交后由 ImageGenService（前台服务）后台执行，切走 App 也会继续；
 * - 任务信息持久化到 SharedPreferences，进程被杀后清理为中断；
 * - 完成/失败通过 Listener 回调给界面（生图对话框内展示结果，可放大、保存到相册）。
 * 同一时间只允许一个生图任务，避免重复扣费。
 */
public class ImageGenManager {
    private static final String PREFS = "numai5_imagegen";
    private static final String KEY_TASK_ID = "taskId";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_HISTORY = "history";
    /** 历史记录条数上限（图片文件会一直保留在私有目录，这里只约束显示列表） */
    private static final int HISTORY_MAX = 100;

    /** 一条历史生成记录（Gson 序列化） */
    public static class HistoryItem {
        public String file;
        public String prompt;
        public String model;
        public long ts;

        public HistoryItem() {
        }

        public HistoryItem(String file, String prompt, String model, long ts) {
            this.file = file;
            this.prompt = prompt;
            this.model = model;
            this.ts = ts;
        }
    }

    /** UI 监听（Activity 注册/注销） */
    public interface Listener {
        void onImageGenFinished(String taskId, boolean success, String detail,
                                List<String> imageFiles);
    }

    private static ImageGenManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final List<Listener> listeners = new ArrayList<>();

    private String activeTaskId;
    private String activeModelName = "";
    private Provider activeProvider;
    private List<String> lastResultFiles;
    /** 是否已做过一次磁盘补录（每个进程只做一次，避免频繁扫目录） */
    private boolean restoredFromDisk;

    private ImageGenManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized ImageGenManager getInstance(Context context) {
        if (instance == null) {
            instance = new ImageGenManager(context);
            instance.restoreFromDisk();
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
     * 取消当前生图任务：断开它的 HTTP 调用、清空该供应商的图片排队。
     * 只影响 {@link ProviderRateLimiter.RequestType#IMAGE}，聊天与模型列表排队不动。
     */
    public void cancel() {
        Provider provider = activeProvider;
        if (provider != null) {
            ProviderRateLimiter.cancelCategory(provider, ProviderRateLimiter.RequestType.IMAGE);
        }
    }

    /** 上次成功生成的文件（生图对话框重新打开时展示） */
    public List<String> getLastResultFiles() {
        return lastResultFiles;
    }

    /** 历史记录（新 → 旧） */
    public synchronized List<HistoryItem> getHistory() {
        String json = prefs.getString(KEY_HISTORY, null);
        if (json == null || json.length() == 0) return new ArrayList<>();
        try {
            List<HistoryItem> list = new Gson().fromJson(json,
                    new TypeToken<List<HistoryItem>>() {
                    }.getType());
            return list != null ? list : new ArrayList<HistoryItem>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 是否存在上次进程被杀留下的未完成任务记录 */
    public boolean hasStaleTask() {
        return activeTaskId == null && prefs.getString(KEY_TASK_ID, null) != null;
    }

    /**
     * 丢弃残留的旧任务记录，避免「生图中」状态永久卡住。
     *
     * 只清任务字段，绝不 prefs.clear()：历史记录（KEY_HISTORY）就存在同一个文件里，
     * 之前用 clear() 会把用户所有历史一并抹掉，导致「关掉绘画窗口后图片再也找不到」。
     */
    public void discardStaleTask() {
        clearActiveTask();
    }

    /** 只清当前任务字段，历史记录保持不动 */
    private void clearActiveTask() {
        prefs.edit().remove(KEY_TASK_ID).remove(KEY_PROMPT).apply();
    }

    /**
     * 提交生图任务（不写入聊天会话），启动前台服务执行。
     *
     * @return 任务 id（失败返回 null）
     */
    public String submit(Context context, Provider provider, ModelDef model,
                         String prompt, String size, int n) {
        return submit(context, provider, model, prompt, size, n, null);
    }

    public String submit(Context context, Provider provider, ModelDef model,
                         String prompt, String size, int n, List<String> refImages) {
        if (provider == null || model == null) return null;
        if (isRunning()) return null;
        if (hasStaleTask()) discardStaleTask();

        String taskId = UUID.randomUUID().toString();
        activeTaskId = taskId;
        activeProvider = provider;
        activeModelName = (model.displayName != null && model.displayName.length() > 0)
                ? model.displayName : model.id;

        prefs.edit()
                .putString(KEY_TASK_ID, taskId)
                .putString(KEY_PROMPT, prompt)
                .apply();

        ImageGenService.start(appContext, provider.id, model.uid(), prompt, size, n, refImages);
        return taskId;
    }

    private synchronized void appendHistory(List<String> files, String prompt, String modelName) {
        if (files == null || files.isEmpty()) return;
        List<HistoryItem> list = getHistory();
        long now = System.currentTimeMillis();
        for (String file : files) {
            list.add(0, new HistoryItem(file, prompt, modelName, now));
        }
        while (list.size() > HISTORY_MAX) {
            list.remove(list.size() - 1);
        }
        prefs.edit().putString(KEY_HISTORY, new Gson().toJson(list)).apply();
    }

    /**
     * 从磁盘补录历史记录。
     *
     * 生图结果落盘在 filesDir/images/gen_*.png；以前历史存在同一个 SharedPreferences 里，
     * 任务结束时被 clear() 清空过。这里把磁盘上有、历史里没有的生成图补回去，
     * 让「关掉绘画窗口后找不到那张图」的情况至少还能在绘画记录里翻到。
     */
    private synchronized void restoreFromDisk() {
        if (restoredFromDisk) return;
        restoredFromDisk = true;
        File dir = new File(appContext.getFilesDir(), "images");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;

        List<HistoryItem> history = getHistory();
        Set<String> known = new HashSet<>();
        for (HistoryItem item : history) {
            if (item != null && item.file != null) known.add(item.file);
        }
        boolean changed = false;
        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith("gen_") || !name.endsWith(".png")) continue;
            if (known.contains(name)) continue;
            history.add(new HistoryItem(name, "", "", file.lastModified()));
            changed = true;
        }
        if (!changed) return;

        Collections.sort(history, (a, b) -> Long.compare(b.ts, a.ts));
        while (history.size() > HISTORY_MAX) {
            history.remove(history.size() - 1);
        }
        prefs.edit().putString(KEY_HISTORY, new Gson().toJson(history)).apply();
    }

    /** 服务完成回调：通知全部监听者（界面负责展示） */
    public void onFinished(List<String> filenames, String errorMessage) {
        String taskId = prefs.getString(KEY_TASK_ID, null);
        // 提示词要在清任务字段之前读出来，否则写进历史里的提示词是空串
        String prompt = prefs.getString(KEY_PROMPT, "");
        clearActiveTask();
        activeTaskId = null;
        activeProvider = null;
        lastResultFiles = (errorMessage == null && filenames != null)
                ? new ArrayList<>(filenames) : null;
        if (lastResultFiles != null) {
            appendHistory(lastResultFiles, prompt, activeModelName);
        }

        for (Listener l : new ArrayList<>(listeners)) {
            l.onImageGenFinished(taskId, errorMessage == null, errorMessage, filenames);
        }
    }
}
