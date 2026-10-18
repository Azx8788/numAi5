package io.github.numai5.ui;

import android.app.Activity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import io.github.numai5.R;

/**
 * 工具按钮自由摆放宿主（纯触摸实现，不依赖系统 DragAndDrop）。
 *
 * 背景：先前的版本用了系统 startDragAndDrop。但那套 API 在部分 OEM ROM 上有
 * 拖影残留/回调竞态问题，且在 Android 5/6（minSdk 21）是 API 24 才有的方法，
 * 直接 NoSuchMethodError 崩回桌面。用户实测出现"拖回输入栏立刻弹回桌面 +
 * 残影停在原处随后自己退回"。这里改用系统底层和可靠得多的纯触摸方案：
 *
 * 规则：
 * - 长按（约 500ms）任一工具按钮进入"拖动模式"：按钮浮起半透明，跟随手指在整个
 *   屏幕内任意移动，短振动提示；
 * - 拖到输入栏附近松手 = 恢复原位；
 * - 其余位置松手 = 停在该处，透明还原，位置按屏幕比例持久化；
 * - 未触发长按前松手（点击）仍是原来的功能（performClick 走原 OnClickListener）。
 */
public class FloatingToolHost {

    /** 位置持久化接口（由调用方接 ConfigManager） */
    public interface Store {
        /** @return "name:xRatio,yRatio;name2:..." 或 null */
        String load();

        void save(String state);
    }

    private static final class Placement {
        float xRatio = -1f; // <0 表示未自定义
        float yRatio = -1f;

        boolean floating() {
            return xRatio >= 0f && yRatio >= 0f;
        }
    }

    private final Activity activity;
    private final ViewGroup toolBar;
    private final Store store;
    private final Map<String, Placement> placements = new HashMap<>();
    private final Map<String, ViewGroup.LayoutParams> dockParams = new HashMap<>();
    private final int touchSlop;
    private final int longPressTimeout;

    private View btnImage;
    private View btnThinking;
    private View btnModel;

    public FloatingToolHost(Activity activity, ViewGroup toolBar, Store store) {
        this.activity = activity;
        this.toolBar = toolBar;
        this.store = store;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        this.longPressTimeout = ViewConfiguration.getLongPressTimeout();
    }

    public void register(String name, View v) {
        if ("btnImage".equals(name)) btnImage = v;
        else if ("btnThinking".equals(name)) btnThinking = v;
        else if ("btnModel".equals(name)) btnModel = v;
    }

    /** Activity 内容就绪后调用一次：恢复持久化位置 + 接管三个按钮的触摸 */
    public void attach() {
        loadState();
        for (Map.Entry<String, Placement> e : placements.entrySet()) {
            if (e.getValue().floating()) {
                View v = findTool(e.getKey());
                if (v != null) makeFloating(v, e.getKey(), e.getValue());
            }
        }
        for (String name : new String[]{"btnImage", "btnThinking", "btnModel"}) {
            View v = findTool(name);
            if (v != null) v.setOnTouchListener(new DragTouch(v, name));
        }
    }

    // ===== 拖动状态机（每个按钮一份） =====

    private final class DragTouch implements View.OnTouchListener {
        private final View view;
        private final String name;

        /** 0=待定（未超过长按窗口） 1=拖动中 2=已判为点击 */
        private int mode;
        private float downX;
        private float downY;
        private int startMarginX;
        private int startMarginY;
        private final Runnable longPressRunnable;

        DragTouch(View v, String toolName) {
            this.view = v;
            this.name = toolName;
            this.longPressRunnable = () -> enterDrag(v);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mode = 0;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startMarginX = marginX(view);
                    startMarginY = marginY(view);
                    v.postDelayed(longPressRunnable, longPressTimeout);
                    return true; // 接管：点击由 performClick 兜底

                case MotionEvent.ACTION_MOVE: {
                    if (mode == 1) {
                        // 拖动中：更新边距
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        FrameLayout.LayoutParams lp = asFrame(view);
                        if (lp != null) {
                            lp.leftMargin = Math.round(startMarginX + dx);
                            lp.topMargin = Math.round(startMarginY + dy);
                            view.setLayoutParams(lp);
                        }
                        return true;
                    }
                    // 长按窗口内移动超过阈值：取消长按，判为点击
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        v.removeCallbacks(longPressRunnable);
                        mode = 2;
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    v.removeCallbacks(longPressRunnable);
                    boolean wasDrag = mode == 1;
                    boolean wasPress = mode == 0;
                    mode = 0;
                    if (wasDrag) {
                        finishDrag(view, name, event.getRawX(), event.getRawY());
                    } else if (wasPress) {
                        view.performClick(); // 原功能（选图/思考/模型）
                    }
                    if (wasDrag) view.setAlpha(1f);
                    return true;
                }

                case MotionEvent.ACTION_CANCEL: {
                    v.removeCallbacks(longPressRunnable);
                    if (mode == 1) {
                        finishDrag(view, name, event.getRawX(), event.getRawY());
                        view.setAlpha(1f);
                    }
                    mode = 0;
                    return true;
                }
            }
            return true;
        }

        private void enterDrag(View v) {
            if (mode != 0) return;
            // 立刻摘到全屏层：否则按钮还挂在 LinearLayout 里，asFrame() 拿不到
            // FrameLayout 边距，拖动时无法更新位置（MOVE 分支会静默失效）
            instanceFloating(v, name);
            ViewGroup root = rootLayout();
            if (root != null) {
                // margin 用 DOWN 时记录的位置（相对 root），避免挂载瞬间跳动
                FrameLayout.LayoutParams lp = asFrame(v);
                if (lp != null) {
                    lp.leftMargin = startMarginX;
                    lp.topMargin = startMarginY;
                    v.setLayoutParams(lp);
                }
            }
            mode = 1;
            v.setAlpha(0.75f);
            try {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } catch (Throwable ignored) {
            }
            Toast.makeText(activity, R.string.tool_drag_hint, Toast.LENGTH_SHORT).show();
        }
    }

    /** 拖动结束：判定落点（输入栏附近 → 恢复原位；其余 → 悬浮 + 持久化） */
    private void finishDrag(View v, String name, float rawX, float rawY) {
        FrameLayout.LayoutParams lp = asFrame(v);
        if (lp == null) return;

        ViewGroup root = rootLayout();
        if (root == null) return;
        if (hitDockArea(rawX, rawY)) {
            dockBack(name);
        } else {
            instanceFloating(v, name);
            // 落点（手指位置）转比例；给按钮在手指上方偏移，让按钮中心大致落在指位上方
            int offset = v.getHeight() > 0 ? Math.round(v.getHeight() * 0.6f) : 0;
            float centerX = rawX - rootScreenX(root);
            float centerY = rawY - rootScreenY(root) - offset;
            Placement p = new Placement();
            p.xRatio = clamp01(centerX / (float) Math.max(1, root.getWidth()));
            p.yRatio = clamp01(centerY / (float) Math.max(1, root.getHeight()));
            placements.put(name, p);
            positionFloating(v, p);
            saveState();
        }
    }

    /** 确保视图挂在 root 上（未挂则摘下来挂上，克隆停靠参数） */
    private void instanceFloating(View v, String name) {
        ViewGroup root = rootLayout();
        if (root == null) return;
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent != root) {
            ViewGroup.LayoutParams old = v.getLayoutParams();
            if (old instanceof LinearLayout.LayoutParams && !dockParams.containsKey(name)) {
                // 只克隆工具栏参数（LinearLayout 含 weight）；若已是 Float 参数则不覆盖
                dockParams.put(name, cloneLayoutParams(old));
            }
            if (parent != null) {
                parent.removeView(v);
            }
            FrameLayout.LayoutParams fp = newFrameParams(v);
            root.addView(v, fp);
        }
        if (v instanceof TextView) {
            int max = Math.round(root.getWidth() * 0.6f);
            if (max > 0) {
                ((TextView) v).setMaxWidth(max);
            }
        }
    }

    /** attach 恢复时用：从工具栏摘下挂到 root */
    private void makeFloating(View v, String name, Placement p) {
        instanceFloating(v, name);
        ViewGroup root = rootLayout();
        if (root == null) return;
        v.post(() -> positionFloating(v, p));
    }

    private void positionFloating(View v, Placement p) {
        ViewGroup root = rootLayout();
        if (root == null) return;
        if (v.getParent() != root) return;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) {
            v.post(() -> positionFloating(v, p));
            return;
        }
        FrameLayout.LayoutParams lp = asFrame(v);
        if (lp == null) return;
        int edge = Math.round(8 * v.getResources().getDisplayMetrics().density);
        int x = Math.round(p.xRatio * root.getWidth()) - w / 2;
        int y = Math.round(p.yRatio * root.getHeight()) - h / 2;
        x = Math.max(edge, Math.min(root.getWidth() - w - edge, x));
        y = Math.max(edge, Math.min(root.getHeight() - h - edge, y));
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        v.setLayoutParams(lp);
    }

    /** 取消悬浮：放回输入栏末尾（weight 等原始参数还原） */
    private void dockBack(String name) {
        View v = findTool(name);
        if (v == null) return;
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent != null) parent.removeView(v);
        if (v instanceof TextView) {
            ((TextView) v).setMaxWidth(Integer.MAX_VALUE);
        }
        ViewGroup.LayoutParams lp = dockParams.get(name);
        if (lp == null || !(lp instanceof LinearLayout.LayoutParams)) {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        toolBar.addView(v, lp);
        placements.remove(name);
        saveState();
    }

    // ===== 工具 =====

    /** 落点是否在输入栏整体区域（工具栏 + 输入行），是则放回原位 */
    private boolean hitDockArea(float rawX, float rawY) {
        View dockArea = toolBar;
        ViewGroup parent = (ViewGroup) toolBar.getParent();
        if (parent != null && parent != rootLayout()) dockArea = parent; // 整个 inputBar
        int[] loc = new int[2];
        dockArea.getLocationOnScreen(loc);
        float tol = 16 * dockArea.getResources().getDisplayMetrics().density;
        return rawX >= loc[0] - tol && rawX <= loc[0] + dockArea.getWidth() + tol
                && rawY >= loc[1] - tol && rawY <= loc[1] + dockArea.getHeight() + tol;
    }

    private FrameLayout.LayoutParams asFrame(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        return lp instanceof FrameLayout.LayoutParams ? (FrameLayout.LayoutParams) lp : null;
    }

    private int marginX(View v) {
        FrameLayout.LayoutParams lp = asFrame(v);
        if (lp != null) return lp.leftMargin;
        ViewGroup root = rootLayout();
        if (root == null) return 0;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[0] - rootScreenX(root);
    }

    private int marginY(View v) {
        FrameLayout.LayoutParams lp = asFrame(v);
        if (lp != null) return lp.topMargin;
        ViewGroup root = rootLayout();
        if (root == null) return 0;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[1] - rootScreenY(root);
    }

    private int rootScreenX(ViewGroup root) {
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        return loc[0];
    }

    private int rootScreenY(ViewGroup root) {
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        return loc[1];
    }

    private FrameLayout.LayoutParams newFrameParams(View v) {
        ViewGroup.LayoutParams old = v.getLayoutParams();
        int w = old != null && old.width > 0 ? old.width : ViewGroup.LayoutParams.WRAP_CONTENT;
        int h = old != null && old.height > 0 ? old.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        return new FrameLayout.LayoutParams(w, h);
    }

    private static ViewGroup.LayoutParams cloneLayoutParams(ViewGroup.LayoutParams src) {
        if (src == null) return null;
        if (src instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) src);
        }
        if (src instanceof FrameLayout.LayoutParams) {
            return new FrameLayout.LayoutParams((FrameLayout.LayoutParams) src);
        }
        if (src instanceof ViewGroup.MarginLayoutParams) {
            return new FrameLayout.LayoutParams((ViewGroup.MarginLayoutParams) src);
        }
        return new FrameLayout.LayoutParams(src.width, src.height);
    }

    private String toolName(View v) {
        int id = v.getId();
        if (id == R.id.btnImage) return "btnImage";
        if (id == R.id.btnThinking) return "btnThinking";
        if (id == R.id.btnModel) return "btnModel";
        return null;
    }

    private View findTool(String name) {
        if ("btnImage".equals(name)) return btnImage;
        if ("btnThinking".equals(name)) return btnThinking;
        if ("btnModel".equals(name)) return btnModel;
        return null;
    }

    private ViewGroup rootLayout() {
        return (ViewGroup) activity.findViewById(android.R.id.content);
    }

    private void loadState() {
        placements.clear();
        String state = store.load();
        if (state == null || state.length() == 0) return;
        try {
            for (String part : state.split(";")) {
                String[] seg = part.split(":");
                if (seg.length != 2) continue;
                String[] xy = seg[1].split(",");
                if (xy.length != 2) continue;
                Placement p = new Placement();
                p.xRatio = Float.parseFloat(xy[0]);
                p.yRatio = Float.parseFloat(xy[1]);
                placements.put(seg[0].trim(), p);
            }
        } catch (Exception ignored) {
        }
    }

    private void saveState() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Placement> e : placements.entrySet()) {
            if (!e.getValue().floating()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append(':')
                    .append(String.format(Locale.ROOT, "%.4f", e.getValue().xRatio))
                    .append(',')
                    .append(String.format(Locale.ROOT, "%.4f", e.getValue().yRatio));
        }
        store.save(sb.toString());
    }

    private static float clamp01(float f) {
        return Math.max(0f, Math.min(1f, f));
    }
}
