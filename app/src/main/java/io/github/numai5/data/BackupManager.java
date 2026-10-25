package io.github.numai5.data;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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

    // ===== 文件选择器缺失时的兜底：直接读写公共下载目录 =====

    /** 兜底目录：Download/numAi5，无 SAF 时用 */
    public static File fallbackDir(Context context) {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(dl, "numAi5");
        if (!dir.exists() && !dir.mkdirs()) {
            // mkdirs 失败时退回 Download 根目录
            return dl;
        }
        return dir;
    }

    /** 导出备份到兜底目录，返回写入的文件 */
    public static File exportToFile(Context context) throws Exception {
        String json = exportJson(context);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        File file = new File(fallbackDir(context), "numAi5-backup-" + stamp + ".json");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        return file;
    }

    /** 扫描兜底目录下的备份文件，按修改时间倒序 */
    public static List<File> scanBackups(Context context) {
        File dir = fallbackDir(context);
        File[] files = dir.listFiles((d, name) ->
                name != null && name.startsWith("numAi5-backup-") && name.endsWith(".json"));
        List<File> list = new ArrayList<>();
        if (files != null && files.length > 0) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) list.add(f);
        }
        return list;
    }

    /** 从文件读取备份（兜底路径） */
    public static Backup readBackup(Context context, File file) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            Backup backup = new Gson().fromJson(reader, Backup.class);
            if (backup == null) throw new Exception("备份文件内容为空或格式不正确");
            if (backup.config == null && (backup.chats == null || backup.chats.isEmpty())) {
                throw new Exception("备份文件中没有可恢复的数据");
            }
            return backup;
        }
    }

    /** 文件显示名（时间 + 大小） */
    public static String fileLabel(File file) {
        if (file == null) return "";
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(file.lastModified()));
        return file.getName() + "  " + time + "  " + (file.length() / 1024) + "KB";
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
