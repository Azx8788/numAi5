package io.github.numai5.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.LruCache;
import ru.noties.jlatexmath.JLatexMathAndroid;
import ru.noties.jlatexmath.JLatexMathDrawable;

/**
 * LaTeX → Drawable（基于 jlatexmath-android）。
 * 带渲染缓存（按「显示尺寸 + 颜色 + 宽度上限 + 公式」为键）；
 * 任何失败都返回 null，由调用方回退显示原文，绝不让公式拖垮聊天界面。
 *
 * 注意：所有渲染串行执行（synchronized），避免字体首次加载时多线程竞态。
 */
public final class MathRenderer {
    private static final String TAG = "MathRenderer";
    /** 缓存上限（按像素面积估计，约 6MB ARGB） */
    private static final int CACHE_MAX_PIXELS = 6 * 1024 * 1024 / 4;
    /** 字号放大倍率：TeX 字体 x 高度偏小，稍放大更接近正文观感 */
    private static final float SIZE_FACTOR = 1.2f;
    /** 与 MathExtractor 一致的保护上限 */
    private static final int MAX_LATEX_LENGTH = 2000;

    private static final LruCache<String, Drawable> CACHE = new LruCache<String, Drawable>(CACHE_MAX_PIXELS) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            return Math.max(1, value.getIntrinsicWidth() * value.getIntrinsicHeight());
        }
    };

    private static volatile boolean initialized;

    private MathRenderer() {
    }

    /**
     * 渲染公式；失败返回 null。
     *
     * @param textSizePx 正文字号（px），内部会再放大 SIZE_FACTOR
     * @param maxWidthPx 可用宽度上限（px），超宽公式等比缩小
     * @param color      公式颜色（跟随所在 TextView 的文字颜色）
     */
    public static synchronized Drawable render(Context context, String latex, boolean display,
                                               float textSizePx, int maxWidthPx, int color) {
        if (context == null || latex == null || latex.length() == 0
                || latex.length() > MAX_LATEX_LENGTH) {
            return null;
        }
        ensureInitialized(context);
        int size = Math.max(9, Math.round(textSizePx * SIZE_FACTOR));
        String key = (display ? "D" : "I") + size + ':' + color + ':' + maxWidthPx + ':' + latex;
        Drawable cached = CACHE.get(key);
        if (cached != null) return cached;
        try {
            JLatexMathDrawable drawable = JLatexMathDrawable.builder(latex)
                    .textSize(size)
                    .color(color)
                    .padding(display ? 6 : 2)
                    .build();
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            if (width <= 0 || height <= 0) return null;
            if (maxWidthPx > 0 && width > maxWidthPx) {
                int scaledHeight = Math.max(1, (int) ((long) height * maxWidthPx / width));
                drawable.setBounds(0, 0, maxWidthPx, scaledHeight);
            } else {
                drawable.setBounds(0, 0, width, height);
            }
            CACHE.put(key, drawable);
            return drawable;
        } catch (Throwable t) {
            // 公式语法不被支持 / 含不可渲染字符（如公式里的中文）等，保持原文显示
            Log.w(TAG, "LaTeX 渲染失败，按原文显示：" + latex, t);
            return null;
        }
    }

    /** 预热：在后台线程调用，提前加载字体；失败不影响使用 */
    public static void warmUp(Context context) {
        try {
            synchronized (MathRenderer.class) {
                ensureInitialized(context);
                JLatexMathDrawable drawable = JLatexMathDrawable.builder("x^{2}+1")
                        .textSize(16)
                        .build();
                int width = Math.max(1, drawable.getIntrinsicWidth());
                int height = Math.max(1, drawable.getIntrinsicHeight());
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, width, height);
                drawable.draw(new Canvas(bitmap));
                bitmap.recycle();
            }
        } catch (Throwable t) {
            Log.w(TAG, "LaTeX 预热失败（不影响使用）", t);
        }
    }

    private static void ensureInitialized(Context context) {
        if (initialized) return;
        try {
            JLatexMathAndroid.init(context.getApplicationContext());
        } catch (Throwable ignored) {
        }
        initialized = true;
    }
}
