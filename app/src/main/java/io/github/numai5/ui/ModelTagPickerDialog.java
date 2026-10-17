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
import java.util.List;
import java.util.Locale;
import io.github.numai5.R;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;

/**
 * 模型能力标签对话框：给每个模型手动标注「思考 / 识图 / 绘图」三种能力。
 *
 * 背景：中转站的模型名千奇百怪，自动识别只是默认值，手动标注永远优先。
 * 与 check.sh 不同，这里不勾选模型本身，而是勾选它的三个能力。
 */
public class ModelTagPickerDialog {

    /** 打开时的初始标注 + 确认后的回调 */
    public interface OnSaveListener {
        void onSave();
    }

    private final Context context;
    private final List<ModelDef> all;
    private final List<ModelDef> visible = new ArrayList<>();
    private final OnSaveListener listener;
    private final int totalCount;

    private ModelAdapter adapter;
    private TextView countView;

    private ModelTagPickerDialog(Context context, List<ModelDef> models, OnSaveListener listener) {
        this.context = context;
        this.all = new ArrayList<>(models);
        this.listener = listener;
        this.totalCount = models.size();
        visible.addAll(all);
    }

    public static void show(Context context, List<ModelDef> models, OnSaveListener listener) {
        new ModelTagPickerDialog(context, models, listener).showDialog();
    }

    private void showDialog() {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_model_tags, null, false);
        final EditText search = content.findViewById(R.id.tagSearch);
        final ListView list = content.findViewById(R.id.tagList);
        final TextView empty = content.findViewById(R.id.tagEmpty);
        countView = content.findViewById(R.id.tagCount);

        adapter = new ModelAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                applyFilter(s == null ? "" : s.toString());
            }
        });

        new AlertDialog.Builder(context)
                .setTitle(R.string.model_tag_title)
                .setView(content)
                .setPositiveButton(R.string.save, (d, w) -> {
                    // 标注已实时写进 ModelDef，这里只是收尾保存
                    if (listener != null) listener.onSave();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
    }

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
                view = LayoutInflater.from(context).inflate(R.layout.item_model_tag, parent, false);
            }
            final ModelDef model = visible.get(position);
            TextView name = view.findViewById(R.id.tagModelName);
            TextView meta = view.findViewById(R.id.tagModelMeta);
            final CheckBox cbThinking = view.findViewById(R.id.tagThinking);
            final CheckBox cbVision = view.findViewById(R.id.tagVision);
            final CheckBox cbImageGen = view.findViewById(R.id.tagImageGen);

            name.setText(model.displayName != null ? model.displayName : model.id);
            StringBuilder sb = new StringBuilder();
            String provider = providerLabel(model);
            if (provider != null) sb.append(provider);
            if (model.id != null && model.id.length() > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(model.id);
            }
            meta.setText(sb.toString());

            // 初始状态：自动推断打底，手动覆盖生效
            cbThinking.setChecked(model.isThinking());
            cbVision.setChecked(model.isVision());
            cbImageGen.setChecked(model.isImageGen());

            cbThinking.setOnCheckedChangeListener((b, checked) -> model.setThinkingTag(checked));
            cbVision.setOnCheckedChangeListener((b, checked) -> model.setVisionTag(checked));
            cbImageGen.setOnCheckedChangeListener((b, checked) -> model.setImageGenTag(checked));
            return view;
        }
    }
}
