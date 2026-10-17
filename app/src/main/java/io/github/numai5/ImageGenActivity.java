package io.github.numai5;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import io.github.numai5.data.ConfigManager;
import io.github.numai5.data.ImageGenManager;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;
import io.github.numai5.vision.ImageCompressor;
import io.github.numai5.ui.ImageLoader;

/**
 * AI 绘图独立页（借鉴 RikkaHub ImgGenPage 的独立页面思路）。
 * 生图不混入聊天会话：模型/尺寸/提示词在这里完成，生成结果在页内展示，
 * 点击可全屏预览、保存到相册，并保留历史生成记录。
 */
public class ImageGenActivity extends Activity implements ImageGenManager.Listener {

    private static final int GRID_COLUMNS = 3;

    private ConfigManager configManager;
    private ModelDef genModel;
    private String genSize;

    private Button btnModel;
    private Button btnSize;
    private EditText editPrompt;
    private Button btnStart;
    private View statusBar;
    private TextView statusText;
    private View resultTitle;
    private LinearLayout resultRow;
    private TextView historyEmpty;
    private RecyclerView historyList;
    private HistoryAdapter historyAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_gen);

        configManager = ConfigManager.getInstance();
        String savedModel = configManager.getImageGenModelUid();
        genSize = configManager.getImageGenSize();

        btnModel = findViewById(R.id.genModel);
        btnSize = findViewById(R.id.genSize);
        editPrompt = findViewById(R.id.genPrompt);
        btnStart = findViewById(R.id.genStart);
        statusBar = findViewById(R.id.genStatus);
        statusText = findViewById(R.id.genStatusText);
        resultTitle = findViewById(R.id.genResultTitle);
        resultRow = findViewById(R.id.genResultRow);
        historyEmpty = findViewById(R.id.genHistoryEmpty);
        historyList = findViewById(R.id.genHistoryList);

        findViewById(R.id.genBack).setOnClickListener(v -> finish());

        btnModel.setOnClickListener(v -> pickModel());
        btnSize.setOnClickListener(v -> pickSize());
        btnStart.setOnClickListener(v -> startGen());

        historyList.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
        historyAdapter = new HistoryAdapter();
        historyList.setAdapter(historyAdapter);

        btnSize.setText(genSize);
        resolveGenModel(savedModel);
        renderModelButton();

        ImageGenManager manager = ImageGenManager.getInstance(this);
        manager.addListener(this);
        if (manager.isRunning()) {
            showRunning();
        }
        // 上次进程被杀留下的中断任务直接清掉
        if (manager.hasStaleTask()) {
            manager.discardStaleTask();
        }
        refreshHistory();
    }

    @Override
    protected void onDestroy() {
        ImageGenManager.getInstance().removeListener(this);
        super.onDestroy();
    }

    private void resolveGenModel(String savedUid) {
        List<ModelDef> models = ModelCatalog.getInstance().getImageGenModels();
        if (models.isEmpty()) return;
        if (savedUid != null) {
            for (ModelDef m : models) {
                if (savedUid.equals(m.uid())) {
                    genModel = m;
                    return;
                }
            }
        }
        genModel = models.get(0);
    }

    private void renderModelButton() {
        if (genModel != null) {
            Provider p = ModelCatalog.getInstance().findProviderOfModel(genModel.uid());
            btnModel.setText(p != null ? p.displayName() + " · " + genModel.displayName
                    : genModel.displayName);
        } else {
            btnModel.setText(R.string.model_not_selected);
        }
    }

    private void pickModel() {
        final List<ModelDef> models = ModelCatalog.getInstance().getImageGenModels();
        if (models.isEmpty()) {
            toast(getString(R.string.imagegen_no_model));
            return;
        }
        String[] labels = new String[models.size()];
        for (int i = 0; i < models.size(); i++) {
            Provider p = ModelCatalog.getInstance().findProviderOfModel(models.get(i).uid());
            labels[i] = (p != null ? p.displayName() + " · " : "") + models.get(i).displayName;
        }
        int checked = genModel != null ? Math.max(0, indexOf(models, genModel.uid())) : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.imagegen_model)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    genModel = models.get(which);
                    configManager.setImageGenModelUid(genModel.uid());
                    renderModelButton();
                    d.dismiss();
                })
                .show();
    }

    private int indexOf(List<ModelDef> models, String uid) {
        for (int i = 0; i < models.size(); i++) {
            if (uid != null && uid.equals(models.get(i).uid())) return i;
        }
        return 0;
    }

    private void pickSize() {
        final String[] sizes = getResources().getStringArray(R.array.imagegen_sizes);
        int checked = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i].equals(genSize)) checked = i;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.imagegen_size)
                .setSingleChoiceItems(sizes, checked, (d, which) -> {
                    genSize = sizes[which];
                    btnSize.setText(genSize);
                    configManager.setImageGenSize(genSize);
                    d.dismiss();
                })
                .show();
    }

    private void startGen() {
        String prompt = editPrompt.getText().toString().trim();
        if (prompt.length() == 0) {
            toast(getString(R.string.imagegen_prompt_empty));
            return;
        }
        if (genModel == null) {
            toast(getString(R.string.imagegen_no_model));
            return;
        }
        Provider provider = ModelCatalog.getInstance().findProviderOfModel(genModel.uid());
        if (provider == null || !provider.isValidForFetch()) {
            toast(getString(R.string.imagegen_provider_invalid));
            return;
        }
        ImageGenManager manager = ImageGenManager.getInstance(this);
        if (manager.isRunning()) {
            toast(getString(R.string.imagegen_busy));
            return;
        }
        String taskId = manager.submit(this, provider, genModel, prompt, genSize, 1);
        if (taskId == null) {
            toast(getString(R.string.imagegen_busy));
            return;
        }
        showRunning();
    }

    private void showRunning() {
        statusBar.setVisibility(View.VISIBLE);
        statusText.setText(R.string.imagegen_background_hint);
        btnStart.setEnabled(false);
        findViewById(R.id.genBusyBadge).setVisibility(View.VISIBLE);
    }

    private void showIdle() {
        statusBar.setVisibility(View.GONE);
        btnStart.setEnabled(true);
        findViewById(R.id.genBusyBadge).setVisibility(View.GONE);
    }

    @Override
    public void onImageGenFinished(String taskId, boolean success, String detail,
                                   List<String> imageFiles) {
        if (isFinishing() || isDestroyed()) return;
        showIdle();
        if (success && imageFiles != null && !imageFiles.isEmpty()) {
            toast(getString(R.string.imagegen_done));
            refreshHistory();
            renderResultRow(imageFiles);
        } else {
            toast(getString(R.string.imagegen_failed_format,
                    detail != null ? detail : ""));
        }
    }

    private void renderResultRow(List<String> imageFiles) {
        resultTitle.setVisibility(View.VISIBLE);
        resultRow.removeAllViews();
        int size = (int) (getResources().getDisplayMetrics().density * 96);
        for (String filename : imageFiles) {
            File file = ImageCompressor.imageFile(this, filename);
            android.graphics.Bitmap bitmap = ImageLoader.load(file, 512);
            if (bitmap == null) continue;
            ImageView view = new ImageView(this);
            view.setImageBitmap(bitmap);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setBackgroundColor(0x22FFFFFF);
            int pad = Math.round(4 * getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            view.setOnClickListener(v -> openPreview(imageFiles, imageFiles.indexOf(filename)));
            resultRow.addView(view);
        }
    }

    private void openPreview(List<String> imageFiles, int index) {
        List<String> paths = new ArrayList<>();
        for (String name : imageFiles) {
            paths.add(ImageCompressor.imageFile(this, name).getAbsolutePath());
        }
        Intent intent = new Intent(this, ImagePreviewActivity.class);
        intent.putStringArrayListExtra(ImagePreviewActivity.EXTRA_FILES, new ArrayList<>(paths));
        intent.putExtra(ImagePreviewActivity.EXTRA_INDEX, index);
        startActivity(intent);
    }

    private void refreshHistory() {
        List<ImageGenManager.HistoryItem> history = ImageGenManager.getInstance(this).getHistory();
        historyEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
        historyAdapter.setItems(history);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ===== 历史列表 =====

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {
        private final List<ImageGenManager.HistoryItem> items = new ArrayList<>();

        void setItems(List<ImageGenManager.HistoryItem> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(ImageGenActivity.this)
                    .inflate(R.layout.item_gen_history, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            ImageGenManager.HistoryItem item = items.get(position);
            File file = new File(item.file);
            android.graphics.Bitmap bitmap = ImageLoader.load(file, 512);
            holder.image.setImageBitmap(bitmap);
            holder.image.setOnClickListener(v -> {
                List<String> one = new ArrayList<>();
                one.add(item.file);
                openPreviewPath(one, 0);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView image;

            Holder(View view) {
                super(view);
                image = view.findViewById(R.id.historyThumb);
            }
        }
    }

    private void openPreviewPath(List<String> paths, int index) {
        Intent intent = new Intent(this, ImagePreviewActivity.class);
        intent.putStringArrayListExtra(ImagePreviewActivity.EXTRA_FILES, new ArrayList<>(paths));
        intent.putExtra(ImagePreviewActivity.EXTRA_INDEX, index);
        startActivity(intent);
    }
}
