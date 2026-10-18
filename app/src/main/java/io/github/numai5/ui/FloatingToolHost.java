package io.github.numai5.ui;

import android.app.Activity;
import android.util.Log;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;
import io.github.numai5.R;

/**
 * 工具按钮自由摆放宿主：长按按钮拖到屏幕任意位置停靠，拖回输入栏恢复原位。
 *
 * 结构：按钮从 toolbar 摘下，挂到 android.R.id.content（Activity 窗口内），
 * 位置按屏幕比例持久化；旋转/重建后按比例还原。无系统悬浮窗权限需求。
 *
 * 拖放处理要点（踩过坑）：
 * - 只在根布局挂一个 OnDragListener：Drop 事件按命中区域向多个监听着派发时，
 *   同一按钮会被多个监听者反复 removeView/addView，触发 "child already has a
 *   parent" 崩溃（表现是拖回输入栏时 App 直接弹回桌面）；
 * - DROP 处理器里绝不动视图树：只记录意图，等 ACTION_DRAG_ENDED 之后再 post 到
 *   主线程队列里实施，避免拖影绘制与视图迁移竞争；
 * - 移动视图前无条件从旧父容器摘除，杜绝重复停靠导致崩溃;
 * - 停靠还原时克隆原始 LayoutParams（含 btnModel 的 weight 拉伸）。
 */
public class FloatingToolHost {

    /** 位置持久化接口（由调用方接 ConfigManager） */
    public interface Store {
        /** @return "name:xRatio,yRatio;name2:..." 或 null */
        String load();

        void save(String state);
    }

    private static final String TAG = "FloatingToolHost";
    private static final int MODE_NONE = 0;
    private static final int MODE_DOCK = 1;
    private static final int MODE_FLOAT = 2;

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

    private View btnImage;
    private View btnThinking;
    private View btnModel;

    // DragAndDrop 意图暂存（DROP 只记录，ENDED 后统一执行）
    private int pendingMode = MODE_NONE;
    private String pendingName;
    private float pendingXRatio;
    private float pendingYRatio;

    public FloatingToolHost(Activity activity, ViewGroup toolBar, Store store) {
        this.activity = activity;
        this.toolBar = toolBar;
        this.store = store;
    }

    public void register(String name, View v) {
        if ("btnImage".equals(name)) btnImage = v;
        else if ("btnThinking".equals(name)) btnThinking = v;
        else if ("btnModel".equals(name)) btnModel = v;
    }

    /** Activity 内容就绪后调用：恢复持久化位置并接管拖放 */
    public void attach() {
        loadState();
        for (String name : placements.keySet()) {
            Placement p = placements.get(name);
            if (p != null && p.floating()) {
                View v = findTool(name);
                if (v != null) makeFloatingNow(v, name, p);
            }
        }
        enableLongPressDrag();
        ViewGroup root = rootLayout();
        if (root != null) {
            root.setOnDragListener(this::handleDragEvents);
        }
    }

    // ===== 拖放（单一监听入口） =====

    private void enableLongPressDrag() {
        for (String name : new String[]{"btnImage", "btnThinking", "btnModel"}) {
            View v = findTool(name);
            if (v == null) continue;
            v.setOnLongClickListener(source -> {
                source.startDragAndDrop(null, new View.DragShadowBuilder(source), source, 0);
                ToastCompat.show(activity, R.string.tool_drag_hint);
                return true;
            });
        }
    }

    private boolean handleDragEvents(View target, DragEvent event) {
        int action = event.getAction();
        if (action == DragEvent.ACTION_DROP) {
            Object state = event.getLocalState();
            if (state instanceof View) {
                String name = toolName((View) state);
                if (name != null) {
                    recordDrop(name, event.getX(), event.getY());
                }
            }
            return true;
        }
        if (action == DragEvent.ACTION_DRAG_ENDED) {
            if (pendingMode != MODE_NONE) {
                ViewGroup root = rootLayout();
                if (root != null) {
                    // 双 post：等拖拽结束、拖影彻底释放后再动视图树
                    root.post(() -> root.post(this::applyPending));
                }
            }
            return true;
        }
        // DRAG_STARTED 必须返回 true 才能持续收到后续事件
        return action == DragEvent.ACTION_DRAG_STARTED || action == DragEvent.ACTION_DRAG_ENTERED
                || action == DragEvent.ACTION_DRAG_LOCATION
                || action == DragEvent.ACTION_DRAG_EXITED;
    }

    /** DROP 时只记录落点意图，不碰视图树 */
    private void recordDrop(String name, float x, float y) {
        ViewGroup root = rootLayout();
        if (root == null) return;
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        float screenX = rootLoc[0] + x;
        float screenY = rootLoc[1] + y;

        pendingName = name;
        if (hitToolBar(screenX, screenY)) {
            pendingMode = MODE_DOCK;
        } else {
            pendingMode = MODE_FLOAT;
            pendingXRatio = clamp01((screenX - rootLoc[0]) / (float) Math.max(1, root.getWidth()));
            pendingYRatio = clamp01((screenY - rootLoc[1]) / (float) Math.max(1, root.getHeight()));
        }
    }

    /** 拖拽完全结束后落地执行 */
    private void applyPending() {
        if (pendingMode == MODE_NONE) return;
        String name = pendingName;
        int mode = pendingMode;
        pendingMode = MODE_NONE;
        pendingName = null;
        try {
            if (mode == MODE_DOCK) {
                dockBack(name);
            } else {
                Placement p = new Placement();
                p.xRatio = clamp01(pendingXRatio);
                p.yRatio = clamp01(pendingYRatio);
                View v = findTool(name);
                if (v != null) {
                    if (v.getParent() == rootLayout()) {
                        positionFloating(v, p);          // 已浮动：原地挪动
                    } else {
                        makeFloatingNow(v, name, p);     // 从工具栏摘下
                    }
                }
                placements.put(name, p);
            }
            saveState();
        } catch (Throwable t) {
            Log.e(TAG, "apply pending placement", t);
        }
    }

    // ===== 悬浮 / 停靠 =====

    private void makeFloatingNow(View v, String name, Placement p) {
        ViewGroup root = rootLayout();
        if (root == null) return;
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent != null) {
            // 克隆原始参数（保存 weight 等），停靠时还原
            dockParams.put(name, cloneLayoutParams(v.getLayoutParams()));
            parent.removeView(v);
        }

        // btnModel 在工具栏里是 0dp+weight（width==0），直接沿用会变成 0 宽不可见
        ViewGroup.LayoutParams old = v.getLayoutParams();
        int w = old.width > 0 ? old.width : ViewGroup.LayoutParams.WRAP_CONTENT;
        int h = old.height > 0 ? old.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(w, h);
        if (old instanceof ViewGroup.MarginLayoutParams) {
            fp.leftMargin = ((ViewGroup.MarginLayoutParams) old).leftMargin;
            fp.topMargin = ((ViewGroup.MarginLayoutParams) old).topMargin;
        }
        // 浮动文本按钮限宽 60% 屏幕，避免超长模型名撑满；停靠时还原
        if (v instanceof TextView) {
            int max = Math.round(root.getWidth() * 0.6f);
            if (max > 0) ((TextView) v).setMaxWidth(max);
        }

        root.addView(v, fp);
        v.post(() -> positionFloating(v, p));
    }

    private void positionFloating(View v, Placement p) {
        ViewGroup root = rootLayout();
        if (root == null || v.getParent() != root) return;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) {
            v.post(() -> positionFloating(v, p));
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        int edge = Math.round(8 * v.getResources().getDisplayMetrics().density);
        int x = Math.round(p.xRatio * root.getWidth()) - w / 2;
        int y = Math.round(p.yRatio * root.getHeight()) - h / 2;
        x = Math.max(edge, Math.min(root.getWidth() - w - edge, x));
        y = Math.max(edge, Math.min(root.getHeight() - h - edge, y));
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.gravity = Gravity.TOP | Gravity.START;
        v.setLayoutParams(lp);
    }

    /** 取消悬浮：无条件从旧父容器摘下，再放回工具栏（按克隆参数还原） */
    private void dockBack(String name) {
        View v = findTool(name);
        if (v == null) return;
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent != null) parent.removeView(v);
        if (v instanceof TextView) {
            ((TextView) v).setMaxWidth(Integer.MAX_VALUE);
        }
        ViewGroup.LayoutParams lp = dockParams.get(name);
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        toolBar.addView(v, lp);
        placements.remove(name);
    }

    private boolean hitToolBar(float screenX, float screenY) {
        int[] loc = new int[2];
        toolBar.getLocationOnScreen(loc);
        float tollerance = 12 * toolBar.getResources().getDisplayMetrics().density;
        return screenX >= loc[0] - tollerance && screenX <= loc[0] + toolBar.getWidth() + tollerance
                && screenY >= loc[1] - tollerance && screenY <= loc[1] + toolBar.getHeight() + tollerance;
    }

    // ===== 持久化 =====

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
                    .append(String.format(java.util.Locale.ROOT, "%.4f", e.getValue().xRatio))
                    .append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.4f", e.getValue().yRatio));
        }
        store.save(sb.toString());
    }

    // ===== 工具 =====

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

    private static float clamp01(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    /** 尽力 Toast：不依赖主题，防个别机型抛异常 */
    private static final class ToastCompat {
        static void show(Activity activity, int resId) {
            try {
                android.widget.Toast.makeText(activity, resId,
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
