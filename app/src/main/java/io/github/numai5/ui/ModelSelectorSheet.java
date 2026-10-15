package io.github.numai5.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.util.ArrayList;
import java.util.List;
import io.github.numai5.R;
import io.github.numai5.data.ConfigManager;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;

/**
 * 模型选择器 BottomSheet：按供应商分组展示，每模型显示能力图标。
 * 默认模型带「默认」徽标（用户规格的 UI 要求）。
 */
public class ModelSelectorSheet extends BottomSheetDialogFragment {

    public interface OnModelSelectedListener {
        void onSelected(ModelDef model);
    }

    private OnModelSelectedListener listener;
    private List<Provider> providers = new ArrayList<>();
    private List<Object> items = new ArrayList<>(); // String(section) 或 ModelDef

    public void setListener(OnModelSelectedListener l) {
        this.listener = l;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.sheet_models, container, false);
        RecyclerView list = view.findViewById(R.id.modelsList);
        list.setLayoutManager(new LinearLayoutManager(getContext()));

        providers = ModelCatalog.getInstance().getProviders();
        String defaultModelId = ConfigManager.getInstance(getContext()).getConfig().defaultModelId;

        // 构建分组数据
        items.clear();
        for (Provider p : providers) {
            List<ModelDef> models = ModelCatalog.getInstance().getModelsByProvider(p.id);
            if (models.isEmpty()) continue;
            items.add(p.name); // section header
            for (ModelDef m : models) items.add(m);
        }

        list.setAdapter(new ModelAdapter(items, defaultModelId));
        return view;
    }

    class ModelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Object> items;
        private final String defaultId;

        ModelAdapter(List<Object> items, String defaultId) {
            this.items = items;
            this.defaultId = defaultId;
        }

        @Override
        public int getItemCount() { return items.size(); }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof String ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == 0) {
                TextView tv = new TextView(parent.getContext());
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                tv.setPadding(16, 12, 16, 4);
                tv.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small);
                return new SectionVH(tv);
            }
            return new ModelVH(inflater.inflate(R.layout.item_model, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = items.get(position);
            if (holder instanceof SectionVH) {
                ((SectionVH) holder).bind((String) item);
            } else {
                ModelDef model = (ModelDef) item;
                ((ModelVH) holder).bind(model, model.id.equals(defaultId));
                ((ModelVH) holder).itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onSelected(model);
                    dismiss();
                });
            }
        }

        class SectionVH extends RecyclerView.ViewHolder {
            SectionVH(View v) { super(v); }
            void bind(String title) { ((TextView) itemView).setText(title); }
        }

        class ModelVH extends RecyclerView.ViewHolder {
            TextView name, meta, badge;
            ModelVH(View v) {
                super(v);
                name = v.findViewById(R.id.modelName);
                meta = v.findViewById(R.id.modelMeta);
                badge = v.findViewById(R.id.defaultBadge);
            }
            void bind(ModelDef model, boolean isDefault) {
                name.setText(model.displayName);
                StringBuilder sb = new StringBuilder();
                sb.append(model.id);
                if (model.supportsThinking) sb.append("  🧠");
                if (model.supportsVision) sb.append("  📷");
                if (model.supportsTools) sb.append("  🔧");
                meta.setText(sb.toString());
                badge.setVisibility(isDefault ? View.VISIBLE : View.GONE);
            }
        }
    }
}
