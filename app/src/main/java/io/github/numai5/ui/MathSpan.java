package io.github.numai5.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import java.util.Arrays;

/**
 * 行内公式图片 Span：占位符范围内的文字由公式图片替换渲染。
 * 图片垂直居中于文本行；行高不足时向上下扩展，避免与相邻行重叠。
 *
 * 同时负责「复制还原」：选区复制时把占位符还原为公式原文（LaTeX），
 * 避免复制出控制字符——通过覆盖选中菜单的「复制」项实现。
 */
public class MathSpan extends ReplacementSpan {

    private final Drawable drawable;
    private final int width;
    private final int height;
    /** 原始文本（含定界符），复制与渲染失败时使用 */
    private final String rawText;

    public MathSpan(Drawable drawable, String rawText) {
        this.drawable = drawable;
        this.rawText = rawText == null ? "" : rawText;
        Rect bounds = drawable.getBounds();
        int w = bounds.width() > 0 ? bounds.width() : drawable.getIntrinsicWidth();
        int h = bounds.height() > 0 ? bounds.height() : drawable.getIntrinsicHeight();
        if (w <= 0) w = 1;
        if (h <= 0) h = 1;
        this.width = w;
        this.height = h;
        drawable.setBounds(0, 0, w, h);
    }

    public String getRawText() {
        return rawText;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (fm != null) {
            Paint.FontMetricsInt origin = paint.getFontMetricsInt();
            int extra = height - (origin.descent - origin.ascent);
            if (extra > 0) {
                fm.ascent = origin.ascent - (extra + 1) / 2;
                fm.descent = origin.descent + extra / 2;
                fm.top = fm.ascent;
                fm.bottom = fm.descent;
            } else {
                fm.ascent = origin.ascent;
                fm.descent = origin.descent;
                fm.top = origin.top;
                fm.bottom = origin.bottom;
            }
        }
        return width;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, Paint paint) {
        int transY = top + (bottom - top - height) / 2;
        canvas.save();
        canvas.translate(x, transY);
        drawable.draw(canvas);
        canvas.restore();
    }

    // ===== 复制还原 =====

    /**
     * 让该 TextView 的「复制」输出公式原文而不是占位符。
     * TextView 自带的复制走内部文本，这里覆盖选中菜单「复制」项的点击行为。
     */
    public static void installCopyFix(final TextView view) {
        if (view == null) return;
        view.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                MenuItem copy = menu.findItem(android.R.id.copy);
                if (copy != null) {
                    copy.setOnMenuItemClickListener(item -> {
                        copyRestored(view);
                        mode.finish();
                        return true;
                    });
                }
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == android.R.id.copy) {
                    copyRestored(view);
                    mode.finish();
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }
        });
    }

    /** 把选区内的公式占位符还原为原文后写入剪贴板 */
    private static void copyRestored(TextView view) {
        try {
            CharSequence full = view.getText();
            if (full == null) return;
            int selStart = Math.max(0, Math.min(view.getSelectionStart(), full.length()));
            int selEnd = Math.max(0, Math.min(view.getSelectionEnd(), full.length()));
            if (selEnd < selStart) {
                int tmp = selStart;
                selStart = selEnd;
                selEnd = tmp;
            }

            String text;
            if (full instanceof Spanned) {
                Spanned spanned = (Spanned) full;
                MathSpan[] spans = spanned.getSpans(selStart, selEnd, MathSpan.class);
                if (spans != null && spans.length > 0) {
                    // 按起点排序后拼接：公式区间用原文，其余按原样
                    Arrays.sort(spans, (a, b) ->
                            spanned.getSpanStart(a) - spanned.getSpanStart(b));
                    StringBuilder sb = new StringBuilder();
                    int cursor = selStart;
                    for (MathSpan span : spans) {
                        int st = Math.max(selStart, spanned.getSpanStart(span));
                        int en = Math.min(selEnd, spanned.getSpanEnd(span));
                        if (st > cursor) sb.append(full.subSequence(cursor, st));
                        sb.append(span.getRawText());
                        cursor = Math.max(cursor, en);
                    }
                    if (cursor < selEnd) sb.append(full.subSequence(cursor, selEnd));
                    text = sb.toString();
                } else {
                    text = full.subSequence(selStart, selEnd).toString();
                }
            } else {
                text = full.subSequence(selStart, selEnd).toString();
            }

            ClipboardManager manager =
                    (ClipboardManager) view.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("text", text));
            }
        } catch (Throwable ignored) {
            // 复制失败不抛异常，避免影响界面
        }
    }
}
