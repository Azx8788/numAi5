package io.github.numai5.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import io.github.numai5.R;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;

/**
 * 模型多选对话框（带搜索）。
 *
 * 用途一：拉取模型后挑选要添加的模型——不少中转站有数百个模型，全部塞进来根本没法用。
 * 用途二：绘图模型标记——跨供应商，需看清每个模型属于谁。
 *
 * 特性：搜索框按「模型名 / 原始 id / 供应商名」过滤；每行显示所属供应商；
 * 同一供应商的模型相邻排列；已选项跨搜索保持（换关键词再勾选不会丢）。
 * 勾选状态一律以 ModelDef#uid 为键，避免同名模型串供应商。
 */
public class ModelPickerDialog {

    public interface OnPickListener {
        void onPick(List<ModelDef> selected);
    }

    private final Context context;
    private final List<ModelDef> all;
    private final List<ModelDef> visible = new ArrayList<>();
    private final Set<String> checkedUids = new HashSet<>();
    private final OnPickListener listener;
    private final String countFormat;
    private final String countFilteredFormat;
    private ModelAdapter adapter;
    private TextView countView;

    private ModelPickerDialog(Context context, List<ModelDef> models,
                             Collection<String> preselected, OnPickListener listener) {
        this.context = context;
        this.all = new ArrayList<>();
        if (models != null) this.all.addAll(models);
        this.listener = listener;
        if (preselected != null) this.checkedUids.addAll(preselected);
        this.countFormat = context.getString(R.string.picker_count_format);
        this.countFilteredFormat = context.getString(R.string.picker_count_filtered_format);
        sortByProvider();
        visible.addAll(all);
    }

    /**
     * @param preselected 初始勾选项，必须是 ModelDef#uid（不是模型 id）——
     *                    同名模型可能同时存在于多个供应商，用 id 会串选。
     */
    public static void show(Context context, String title, List<ModelDef> models,
                            Collection<String> preselected, OnPickListener listener) {
        new ModelPickerDialog(context, models, preselected, listener).showDialog(title);
    }

    /** 同一供应商的模型排在一起，便于成组查看 */
    private void sortByProvider() {
        final List<String> order = new ArrayList<>();
        for (ModelDef m : all) {
            String key = m.providerId == null ? "" : m.providerId;
            if (!order.contains(key)) order.add(key);
        }
        java.util.Collections.sort(all, (a, b) -> {
            int pa = order.indexOf(a.providerId == null ? "" : a.providerId);
            int pb = order.indexOf(b.providerId == null ? "" : b.providerId);
            if (pa != pb) return Integer.compare(pa, pb);
            String na = a.displayName == null ? "" : a.displayName;
            String nb = b.displayName == null ? "" : b.displayName;
            return na.compareToIgnoreCase(nb);
        });
    }

    private void showDialog(String title) {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_model_picker, null, false);
        final EditText search = content.findViewById(R.id.pickerSearch);
        final ListView list = content.findViewById(R.id.pickerList);
        final TextView empty = content.findViewById(R.id.pickerEmpty);
        countView = content.findViewById(R.id.pickerCount);

        adapter = new ModelAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            String uid = visible.get(position).uid();
            if (checkedUids.contains(uid)) {
                checkedUids.remove(uid);
            } else {
                checkedUids.add(uid);
            }
            adapter.notifyDataSetChanged();
            updateCount();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(s == null ? "" : s.toString());
            }
        });

        updateCount();
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(content)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    List<ModelDef> selected = new ArrayList<>();
                    for (ModelDef m : all) {
                        if (checkedUids.contains(m.uid())) selected.add(m);
                    }
                    if (listener != null) listener.onPick(selected);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateCount() {
        if (countView == null) return;
        if (visible.size() == all.size()) {
            countView.setText(String.format(Locale.US, countFormat, checkedUids.size(), all.size()));
        } else {
            countView.setText(String.format(Locale.US, countFilteredFormat,
                    checkedUids.size(), visible.size(), all.size()));
        }
    }

    private void applyFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visible.clear();
        if (q.length() == 0) {
            visible.addAll(all);
        } else {
            for (ModelDef m : all) {
                if (matches(m, q)) visible.add(m);
            }
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    /** 模型名 / 原始 id / 供应商名都参与匹配 */
    private boolean matches(ModelDef m, String q) {
        if (m.id != null && m.id.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (m.displayName != null && m.displayName.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (m.rawId != null && m.rawId.toLowerCase(Locale.ROOT).contains(q)) return true;
        String provider = providerLabel(m);
        return provider != null && provider.toLowerCase(Locale.ROOT).contains(q);
    }

    private String providerLabel(ModelDef m) {
        if (m.providerId == null || m.providerId.length() == 0) return null;
        Provider p = ModelCatalog.getInstance().findProvider(m.providerId);
        return p != null ? p.displayName() : m.providerId;
    }

    private class ModelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Object getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(context)
                        .inflate(R.layout.item_model_check, parent, false);
            }
            ModelDef model = visible.get(position);
            CheckBox check = view.findViewById(R.id.modelCheck);
            TextView name = view.findViewById(R.id.modelCheckName);
            TextView meta = view.findViewById(R.id.modelCheckMeta);

            check.setChecked(checkedUids.contains(model.uid()));
            name.setText(model.displayName != null ? model.displayName : model.id);

            StringBuilder sb = new StringBuilder();
            String provider = providerLabel(model);
            if (provider != null) sb.append(provider);
            if (model.id != null && model.id.length() > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(model.id);
            }
            if (model.isImageGen()) sb.append("  🎨");
            meta.setText(sb.toString());
            return view;
        }
    }
}
