package io.github.numai5.ui;

import android.app.Activity;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.HashMap;
import java.util.Map;
import io.github.numai5.R;

/**
 * 工具按钮自由摆放宿主：把工具按钮从工具栏"摘"出来，挂到全屏 FrameLayout 上，
 * 长按拖动可放到屏幕任意位置；位置按屏幕比例持久化（配置读取回调由外部提供）。
 *
 * 设计要点：
 * - 按钮仍在视图树上，点击事件照常工作；只是父容器换成了全屏 overlay；
 * - 拖动用系统 DragAndDrop（与原排序一致的交互习惯），ACTION_DROP 的落点即新位置；
 * - 悬浮按钮默认吸边（左右居中可选），存比例坐标，旋转/分屏后按比例还原；
 * - 低开销：无服务、无 SYSTEM_ALERT_WINDOW 权限（仅在自家 Activity 窗口内摆放）。
 */
public class FloatingToolHost {

    /** 位置持久化接口（由调用方接 ConfigManager） */
    public interface Store {
        /** @return "name:xRatio,yRatio;name2:..." 或 null */
        String load();
        void save(String state);
    }

    private static final class Placement {
        float xRatio = -1f; // <0 表示未自定义，留在工具栏原位
        float yRatio = -1f;
        boolean floating() {
            return xRatio >= 0f && yRatio >= 0f;
        }
    }

    private final Activity activity;
    private final ViewGroup toolBar;
    private final Store store;
    private final Map<String, Placement> placements = new HashMap<>();
    private final Map<String, FrameLayout.LayoutParams> barParams = new HashMap<>();

    public FloatingToolHost(Activity activity, ViewGroup toolBar, Store store) {
        this.activity = activity;
        this.toolBar = toolBar;
        this.store = store;
    }

    /** 在 Activity 内容就绪后调用：恢复持久化位置并接管拖动 */
    public void attach() {
        loadState();
        for (String name : placements.keySet()) {
            Placement p = placements.get(name);
            if (p != null && p.floating()) {
                View v = findTool(name);
                if (v != null) makeFloating(v, name, p);
            }
        }
        enableDragEverywhere();
    }

    // ===== 拖动 =====

    private void enableDragEverywhere() {
        for (String name : new String[]{"btnImage", "btnThinking", "btnModel"}) {
            View v = findTool(name);
            if (v == null) continue;
            v.setOnLongClickListener(source -> {
                source.startDragAndDrop(null, new View.DragShadowBuilder(source), source, 0);
                android.widget.Toast.makeText(activity, R.string.tool_drag_hint,
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            });
            // 全屏 overlay 接收 DROP：浮动按钮自身也要能接收（拖到别的浮动按钮上）
            v.setOnDragListener(this::handleDrop);
        }
        // 在工具栏与根布局上接 DROP（拖回工具栏 = 取消悬浮）
        toolBar.setOnDragListener(this::handleDrop);
        ViewGroup root = rootLayout();
        if (root != null) root.setOnDragListener(this::handleDrop);
    }

    private boolean handleDrop(View target, DragEvent event) {
        if (event.getAction() != DragEvent.ACTION_DROP) return true;
        Object state = event.getLocalState();
        if (!(state instanceof View)) return true;
        View dragged = (View) state;
        String name = toolName(dragged);
        if (name == null) return true;

        // 拖回工具栏：取消悬浮，回到原排序位
        if (isInside(target, toolBar) || target.getId() == toolBar.getId()) {
            dockBack(name);
            return true;
        }

        // 拖到屏幕其它位置：按落点计算比例坐标并悬浮
        ViewGroup root = rootLayout();
        if (root == null) return true;
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        // event 坐标是相对 target 的，统一换算成屏幕坐标再转比例
        int[] tLoc = new int[2];
        target.getLocationOnScreen(tLoc);
        float screenX = tLoc[0] + event.getX();
        float screenY = tLoc[1] + event.getY();
        float xRatio = clamp01((screenX - loc[0]) / (float) root.getWidth());
        float yRatio = clamp01((screenY - loc[1]) / (float) root.getHeight());

        Placement p = new Placement();
        p.xRatio = xRatio;
        p.yRatio = yRatio;
        makeFloating(dragged, name, p);
        placements.put(name, p);
        saveState();
        return true;
    }

    // ===== 悬浮 / 停靠 =====

    private void makeFloating(View v, String name, Placement p) {
        ViewGroup root = rootLayout();
        if (root == null || v.getParent() == root) return;
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent == null) return;

        barParams.put(name, v.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) v.getLayoutParams()
                : new FrameLayout.LayoutParams(wrap(v), wrap(v)));

        ViewGroup.LayoutParams old = v.getLayoutParams();
        // 注意：btnModel 在工具栏里是 0dp+weight（width==0），直接沿用会变成 0 宽（不可见）；
        // 只有正数值才是明确的固定尺寸，其余（0 / MATCH_PARENT / WRAP_CONTENT）一律转 WRAP_CONTENT
        int w = old.width > 0 ? old.width : ViewGroup.LayoutParams.WRAP_CONTENT;
        int h = old.height > 0 ? old.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(w, h);
        if (old instanceof ViewGroup.MarginLayoutParams) {
            fp.leftMargin = ((ViewGroup.MarginLayoutParams) old).leftMargin;
            fp.topMargin = ((ViewGroup.MarginLayoutParams) old).topMargin;
        }

        // 浮动时限制文本按钮最大宽度（屏幕的 60%），避免超长模型名撑满整屏；停靠时还原
        if (v instanceof android.widget.TextView) {
            int max = Math.round(root.getWidth() * 0.6f);
            if (max > 0) ((android.widget.TextView) v).setMaxWidth(max);
        }

        parent.removeView(v);
        root.addView(v, fp);

        // 等布局完成后再定位（需要真实尺寸）
        v.post(() -> positionFloating(v, p));
    }

    private int wrap(View v) {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
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
        // 以按钮中心为锚点，吸边内缩 8dp 防止拖出一半
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

    /** 取消悬浮：放回工具栏原位（按 barParams 还原布局参数与宽度限制，追加到末尾） */
    private void dockBack(String name) {
        View v = findTool(name);
        if (v == null) return;
        ViewGroup root = rootLayout();
        if (root != null && v.getParent() == root) root.removeView(v);
        if (v instanceof android.widget.TextView) {
            ((android.widget.TextView) v).setMaxWidth(Integer.MAX_VALUE);
        }
        ViewGroup.LayoutParams lp = barParams.get(name);
        toolBar.addView(v, lp != null ? lp : generateDefault(v));
        placements.remove(name);
        saveState();
    }

    private ViewGroup.LayoutParams generateDefault(View v) {
        // toolBar 是 LinearLayout；generateDefaultLayoutParams 是 protected，
        // 这里按等效语义手工构造（水平 LinearLayout 的默认：WRAP_CONTENT）
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return lp;
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

    private String toolName(View v) {
        int id = v.getId();
        if (id == R.id.btnImage) return "btnImage";
        if (id == R.id.btnThinking) return "btnThinking";
        if (id == R.id.btnModel) return "btnModel";
        return null;
    }

    private View findTool(String name) {
        // 浮动中的按钮不在 toolBar 里，也要能找到
        if (btnImage != null && "btnImage".equals(name)) return btnImage;
        if (btnThinking != null && "btnThinking".equals(name)) return btnThinking;
        if (btnModel != null && "btnModel".equals(name)) return btnModel;
        return null;
    }

    private View btnImage;
    private View btnThinking;
    private View btnModel;

    public void register(String name, View v) {
        if ("btnImage".equals(name)) btnImage = v;
        else if ("btnThinking".equals(name)) btnThinking = v;
        else if ("btnModel".equals(name)) btnModel = v;
    }

    private ViewGroup rootLayout() {
        return (ViewGroup) activity.findViewById(android.R.id.content);
    }

    private static boolean isInside(View target, ViewGroup container) {
        while (target != null) {
            if (target == container) return true;
            if (!(target.getParent() instanceof View)) return false;
            target = (View) target.getParent();
        }
        return false;
    }

    private static float clamp01(float f) {
        return Math.max(0f, Math.min(1f, f));
    }
}
