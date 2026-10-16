package io.github.numai5;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;
import io.github.numai5.ui.MathRenderer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃日志记录。
 * 用户多在真机上使用、无法提供 logcat，因此崩溃时把堆栈写入 filesDir/last_crash.txt，
 * 之后可在主界面「关于」里查看并复制，便于快速定位问题。
 */
public class NumAi5App extends Application {
    private static final String CRASH_FILE = "last_crash.txt";
    /** 单条日志上限，避免堆栈过大 */
    private static final int MAX_LENGTH = 64 * 1024;

    @Override
    public void onCreate() {
        super.onCreate();
        // 预热 LaTeX 渲染：字体首次加载较慢，放到后台线程，避免首条公式卡顿
        new Thread(() -> MathRenderer.warmUp(this)).start();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                record(throwable);
                Toast.makeText(getApplicationContext(),
                        R.string.crash_recorded, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
                // 记录失败不能再抛异常，否则会掩盖原始崩溃
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    public static File crashFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), CRASH_FILE);
    }

    /** 读取上次崩溃日志；不存在返回 null */
    public static String readCrash(Context context) {
        File file = crashFile(context);
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), MAX_LENGTH)];
            int read = in.read(data);
            if (read <= 0) return null;
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 清除已查看的崩溃日志 */
    public static void clearCrash(Context context) {
        File file = crashFile(context);
        if (file.exists()) file.delete();
    }

    private void record(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        sb.append("版本: ").append(BuildConfig.VERSION_NAME)
                .append(" (vc").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");
        sb.append(Log.getStackTraceString(throwable));

        String text = sb.toString();
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        try (FileOutputStream out = new FileOutputStream(crashFile(this))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
