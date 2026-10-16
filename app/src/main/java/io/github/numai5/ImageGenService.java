package io.github.numai5;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.List;
import io.github.numai5.api.ApiError;
import io.github.numai5.api.ImageGenApi;
import io.github.numai5.data.ConfigManager;
import io.github.numai5.data.ImageGenManager;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;

/**
 * 生图后台服务（前台服务）。
 * 前台服务 + 常驻通知，保证切走 App / 锁屏时任务继续执行；
 * 完成后写入结果、更新通知，并通知 ImageGenManager 刷新 UI。
 */
public class ImageGenService extends Service {
    private static final String TAG = "ImageGenService";
    private static final String CHANNEL_ID = "imagegen";
    private static final int NOTIF_ONGOING = 1001;
    private static final int NOTIF_RESULT = 1002;

    private static final String EXTRA_PROVIDER_ID = "providerId";
    private static final String EXTRA_MODEL_ID = "modelId";
    private static final String EXTRA_PROMPT = "prompt";
    private static final String EXTRA_SIZE = "size";
    private static final String EXTRA_N = "n";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void start(Context context, String providerId, String modelId,
                             String prompt, String size, int n) {
        Intent intent = new Intent(context, ImageGenService.class);
        intent.putExtra(EXTRA_PROVIDER_ID, providerId);
        intent.putExtra(EXTRA_MODEL_ID, modelId);
        intent.putExtra(EXTRA_PROMPT, prompt);
        intent.putExtra(EXTRA_SIZE, size);
        intent.putExtra(EXTRA_N, n);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须立即进入前台（Android 8+ 要求 5 秒内），否则会被系统判定 ANR 并杀进程
        startForegroundCompat(getString(R.string.imagegen_notification_title),
                getString(R.string.imagegen_notification_running));

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String providerId = intent.getStringExtra(EXTRA_PROVIDER_ID);
        final String modelId = intent.getStringExtra(EXTRA_MODEL_ID);
        final String prompt = intent.getStringExtra(EXTRA_PROMPT);
        final String size = intent.getStringExtra(EXTRA_SIZE);
        final int n = intent.getIntExtra(EXTRA_N, 1);

        new Thread(() -> runTask(providerId, modelId, prompt, size, n)).start();
        return START_NOT_STICKY;
    }

    private void runTask(String providerId, String modelId, String prompt, String size, int n) {
        List<String> filenames = null;
        String error = null;
        try {
            Provider provider = ConfigManager.getInstance().findProvider(providerId);
            ModelDef model = ConfigManager.getInstance().findModel(modelId);
            if (provider == null || model == null) {
                error = "供应商或模型已不存在";
            } else {
                ImageGenApi.Outcome outcome = ImageGenApi.generate(
                        getApplicationContext(), provider, model.requestId(), prompt, size, n);
                filenames = outcome.filenames;
                if (outcome.revisedPrompt != null) {
                    Log.i(TAG, "revised prompt: " + outcome.revisedPrompt);
                }
            }
        } catch (ApiError e) {
            error = e.getMessage();
        } catch (Exception e) {
            Log.e(TAG, "image gen failed", e);
            error = e.getMessage() != null ? e.getMessage() : "未知错误";
        }

        final List<String> resultFiles = filenames;
        final String resultError = error;
        mainHandler.post(() -> finishTask(resultFiles, resultError));
    }

    private void finishTask(List<String> filenames, String error) {
        ImageGenManager manager = ImageGenManager.getInstance();
        if (manager != null) {
            manager.onFinished(filenames, error);
        }

        String title = error == null
                ? getString(R.string.imagegen_notification_done)
                : getString(R.string.imagegen_notification_failed);
        String text = error == null ? "" : error;
        notifyResult(title, text);

        stopForegroundCompat();
        stopSelf();
    }

    // ===== 通知 =====

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.imagegen_channel_name), NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String text, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pending)
                .build();
    }

    private void startForegroundCompat(String title, String text) {
        ensureChannel();
        Notification notification = buildNotification(title, text, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ONGOING, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void notifyResult(String title, String text) {
        ensureChannel();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_RESULT, buildNotification(title, text, false));
        }
    }
}
