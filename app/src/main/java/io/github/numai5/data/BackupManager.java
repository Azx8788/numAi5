package io.github.numai5.data;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.github.numai5.model.Chat;
import io.github.numai5.model.Config;
import io.github.numai5.provider.Provider;

/**
 * 整体备份与恢复（单个 JSON 文件）。
 * 备份内容：全部聊天记录 + 供应商（含地址与 Key）+ 默认模型 + 系统提示词 + 各项开关。
 * 导出/导入通过 SAF（用户自选位置），无需存储权限。
 */
public class BackupManager {
    private static final String TAG = "BackupManager";
    public static final int BACKUP_VERSION = 1;

    public static class Backup {
        public int version = BACKUP_VERSION;
        public long exportedAt;
        public String appVersion;
        public Config config;
        public List<Chat> chats = new ArrayList<>();
    }

    /** 生成备份 JSON */
    public static String exportJson(Context context) {
        Backup backup = new Backup();
        backup.exportedAt = System.currentTimeMillis();
        backup.appVersion = io.github.numai5.BuildConfig.VERSION_NAME;
        backup.config = ConfigManager.getInstance(context).getConfig();
        backup.chats = ChatManager.getInstance(context).getChats();
        return new GsonBuilder().setPrettyPrinting().create().toJson(backup);
    }

    /** 导出到用户选择的位置 */
    public static void exportTo(Context context, Uri uri) throws Exception {
        String json = exportJson(context);
        OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
        if (out == null) throw new Exception("无法写入所选位置");
        try {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    /** 读取备份文件（预览用） */
    public static Backup readBackup(Context context, Uri uri) throws Exception {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取所选文件");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Backup backup = new Gson().fromJson(reader, Backup.class);
            if (backup == null) throw new Exception("备份文件内容为空或格式不正确");
            if (backup.config == null && (backup.chats == null || backup.chats.isEmpty())) {
                throw new Exception("备份文件中没有可恢复的数据");
            }
            return backup;
        }
    }

    /**
     * 恢复备份：覆盖当前的供应商配置与全部聊天记录。
     * 返回恢复的聊天条数。
     */
    public static int restore(Context context, Backup backup) {
        if (backup == null) return 0;

        if (backup.config != null) {
            ConfigManager.getInstance(context).importConfig(backup.config);
        }

        List<Chat> chats = backup.chats != null ? backup.chats : new ArrayList<>();
        for (Chat c : chats) {
            if (c.messages == null) c.messages = new ArrayList<>();
        }
        ChatManager.getInstance(context).replaceAll(chats);
        Log.i(TAG, "restored " + chats.size() + " chats");
        return chats.size();
    }

    /** 备份摘要文本（确认对话框用） */
    public static String describe(Backup backup) {
        if (backup == null) return "";
        int providers = backup.config != null && backup.config.providers != null
                ? backup.config.providers.size() : 0;
        int chats = backup.chats != null ? backup.chats.size() : 0;
        int messages = 0;
        if (backup.chats != null) {
            for (Chat c : backup.chats) {
                if (c.messages != null) messages += c.messages.size();
            }
        }
        return "聊天 " + chats + " 个 · 消息 " + messages + " 条 · 供应商 " + providers + " 个";
    }
}
