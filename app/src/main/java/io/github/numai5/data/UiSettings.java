package io.github.numai5.data;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

/**
 * 全局界面设置：界面大小（整体缩放）与界面边距（百分比），用于适配布局千奇百怪的
 * 安卓 5 低配设备与刘海/挖孔/曲屏边缘。
 *
 * 实现方式：
 * - 缩放通过 attachBaseContext 创建带自定义 densityDpi 的 Configuration，dp/sp 同比例放大缩小，
 *   所有页面一并生效（不逐页改字号，避免漏改）；
 * - 边距通过给 android.R.id.content 加 padding（按屏幕宽高的百分比），避开不可用边缘；
 * - 设置变更用 generation 计数广播：各页面 onResume 检测到变化自行 recreate，立即生效；
 * - 调整页（TuneActivity）自身不应用缩放与边距，保证把数值调坏后仍能操作恢复。
 */
public final class UiSettings {

    private static final String PREFS = "numai5_ui";
    private static final String KEY_SCALE = "uiScale";
    private static final String KEY_MARGIN_H = "uiMarginH";
    private static final String KEY_MARGIN_V = "uiMarginV";
    private static final String KEY_TUNED = "uiTuned";
    private static final String KEY_GENERATION = "uiGeneration";

    /** 界面大小范围（与调整页提示一致） */
    public static final float SCALE_MIN = 0.25f;
    public static final float SCALE_MAX = 5.0f;
    /** 界面边距上限（百分比），超过不保存 */
    public static final float MARGIN_MAX = 30f;

    /** Activity 上记录「已应用的 generation」，用于变更后自动重建 */
    private static final int TAG_GEN = 0x7f0e0001;

    private UiSettings() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static float getScale(Context c) {
        return clampScale(prefs(c).getFloat(KEY_SCALE, 1f));
    }

    public static float getMarginH(Context c) {
        return clampMargin(prefs(c).getFloat(KEY_MARGIN_H, 0f));
    }

    public static float getMarginV(Context c) {
        return clampMargin(prefs(c).getFloat(KEY_MARGIN_V, 0f));
    }

    public static boolean isTuned(Context c) {
        return prefs(c).getBoolean(KEY_TUNED, false);
    }

    public static int getGeneration(Context c) {
        return prefs(c).getInt(KEY_GENERATION, 0);
    }

    /** 保存设置（数值越界自动夹紧：缩放 0.25~5.0，边距 0~30） */
    public static void save(Context c, float scale, float marginH, float marginV) {
        prefs(c).edit()
                .putFloat(KEY_SCALE, clampScale(scale))
                .putFloat(KEY_MARGIN_H, clampMargin(marginH))
                .putFloat(KEY_MARGIN_V, clampMargin(marginV))
                .putInt(KEY_GENERATION, getGeneration(c) + 1)
                .apply();
    }

    /** 恢复默认（1.0 倍、无边距） */
    public static void resetToDefault(Context c) {
        save(c, 1f, 0f, 0f);
    }

    public static void markTuned(Context c) {
        prefs(c).edit().putBoolean(KEY_TUNED, true).apply();
    }

    public static boolean isDefault(Context c) {
        return Math.abs(getScale(c) - 1f) < 0.001f
                && getMarginH(c) <= 0.001f && getMarginV(c) <= 0.001f;
    }

    // ===== 页面接入 =====

    /** attachBaseContext 里调用：按设置返回应用了整体缩放的 Context */
    public static Context wrap(Context base) {
        float scale = getScale(base);
        if (Math.abs(scale - 1f) < 0.01f) return base;
        try {
            Configuration cfg = new Configuration();
            cfg.setTo(base.getResources().getConfiguration());
            int dpi = Math.max(72, Math.round(cfg.densityDpi * scale));
            cfg.densityDpi = dpi;
            return base.createConfigurationContext(cfg);
        } catch (Throwable t) {
            return base; // 个别 ROM 异常时退回原样，保证能启动
        }
    }

    /** onPostCreate 里调用：应用边距并记录当前 generation */
    public static void applyMargins(Activity activity) {
        float h = getMarginH(activity);
        float v = getMarginV(activity);
        if (h > 0 || v > 0) {
            View content = activity.findViewById(android.R.id.content);
            if (content instanceof ViewGroup) {
                DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                int px = Math.round(dm.widthPixels * h / 100f);
                int py = Math.round(dm.heightPixels * v / 100f);
                content.setPadding(px, py, px, py);
            }
        }
        if (activity.getWindow() != null) {
            activity.getWindow().getDecorView().setTag(TAG_GEN, getGeneration(activity));
        }
    }

    /** onResume 里调用：设置在别处改过则自动重建当前页面，立即生效 */
    public static void checkGeneration(Activity activity) {
        if (activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        Object tag = decor.getTag(TAG_GEN);
        int current = getGeneration(activity);
        if (tag instanceof Integer && (Integer) tag != current) {
            decor.setTag(TAG_GEN, current);
            try {
                activity.recreate();
            } catch (Throwable ignored) {
            }
        }
    }

    // ===== 校验 =====

    public static float clampScale(float v) {
        if (Float.isNaN(v)) return 1f;
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, v));
    }

    public static float clampMargin(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(MARGIN_MAX, v));
    }
}
