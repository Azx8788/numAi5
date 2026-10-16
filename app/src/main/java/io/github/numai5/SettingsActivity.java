package io.github.numai5;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.switchmaterial.SwitchMaterial;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import io.github.numai5.api.ApiCallback;
import io.github.numai5.api.ApiError;
import io.github.numai5.api.ApiService;
import io.github.numai5.data.BackupManager;
import io.github.numai5.data.ConfigManager;
import io.github.numai5.model.Config;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;
import io.github.numai5.ui.ModelPickerDialog;
import io.github.numai5.ui.ModelSelectorSheet;

/**
 * 设置页：多供应商管理（增删改，不限个数）+ 默认模型 + 系统提示词 + NewAPI 方言开关。
 * 每个供应商独立配置名称 / 中转地址 / API Key / 模型列表。
 */
public class SettingsActivity extends AppCompatActivity {

    private LinearLayout providerList;
    private Button btnAddProvider, btnDefaultModel, btnSave, btnExportBackup, btnImportBackup, btnImageGenTag;
    private EditText editSystemPrompt;
    private SwitchMaterial switchNewapi;
    private ConfigManager configManager;
    private String selectedDefaultModelId;

    private static final int REQ_EXPORT = 201;
    private static final int REQ_IMPORT = 202;

    /**
     * 注意：不能在字段初始化里创建（如 `= new ApiService(this)`）。
     * 字段初始化在 Activity 构造函数中执行，早于 attach()，此时 getApplicationContext() 会抛 NPE，
     * 表现为「一打开设置页就闪退」。必须在 onCreate 之后创建。
     */
    private ApiService apiService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings);

        configManager = ConfigManager.getInstance(this);
        Config config = configManager.getConfig();
        apiService = new ApiService(this); // 见字段注释：必须在 attach 之后创建

        providerList = findViewById(R.id.providerList);
        btnAddProvider = findViewById(R.id.btnAddProvider);
        btnDefaultModel = findViewById(R.id.btnDefaultModel);
        btnSave = findViewById(R.id.btnSave);
        btnExportBackup = findViewById(R.id.btnExportBackup);
        btnImportBackup = findViewById(R.id.btnImportBackup);
        btnImageGenTag = findViewById(R.id.btnImageGenTag);
        editSystemPrompt = findViewById(R.id.editSystemPrompt);
        switchNewapi = findViewById(R.id.switchNewapi);

        editSystemPrompt.setText(config.systemPrompt);
        switchNewapi.setChecked(config.newapiDialect);
        selectedDefaultModelId = config.defaultModelId;

        renderProviders();
        updateDefaultModelButton();

        btnAddProvider.setOnClickListener(v -> {
            Provider p = configManager.addProvider();
            renderProviders();
            showProviderDialog(p); // 新建后直接进入编辑
        });
        btnDefaultModel.setOnClickListener(v -> showModelSelector());
        btnSave.setOnClickListener(v -> save());
        btnExportBackup.setOnClickListener(v -> startExport());
        btnImportBackup.setOnClickListener(v -> startImport());
        btnImageGenTag.setOnClickListener(v -> showImageGenTagDialog());
    }

    // ===== 绘图模型标记 =====

    /**
     * 手动标记哪些模型是绘图模型。
     * 自动识别（按名字猜）只作默认值，这里勾选的结果优先级更高——中转站的模型名不一定规范，
     * 漏判时可以手动勾上，误判时可以手动取消。跨供应商，对话框内显示归属并提供搜索。
     */
    private void showImageGenTagDialog() {
        final List<ModelDef> models = ModelCatalog.getInstance().getAllModels();
        if (models.isEmpty()) {
            Toast.makeText(this, R.string.no_models_hint, Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> preselected = new ArrayList<>();
        for (ModelDef m : models) {
            if (m.isImageGen()) preselected.add(m.uid());
        }

        ModelPickerDialog.show(this, getString(R.string.imagegen_tag_button),
                models, preselected, new ModelPickerDialog.OnPickListener() {
                    @Override
                    public void onPick(List<ModelDef> selected) {
                        Set<String> selectedUids = new HashSet<>();
                        for (ModelDef m : selected) selectedUids.add(m.uid());
                        for (ModelDef m : models) {
                            m.setImageGenTag(selectedUids.contains(m.uid()));
                        }
                        configManager.refresh(); // 一次性保存
                        Toast.makeText(SettingsActivity.this,
                                getString(R.string.imagegen_tag_saved, selected.size()),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ===== 备份与恢复 =====

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "numAi5-backup-" + stamp + ".json");
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(this, R.string.backup_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, R.string.backup_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            exportTo(uri);
        } else if (requestCode == REQ_IMPORT) {
            confirmImport(uri);
        }
    }

    private void exportTo(final Uri uri) {
        Toast.makeText(this, R.string.backup_exporting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            try {
                BackupManager.exportTo(SettingsActivity.this, uri);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String result = error;
            runOnUiThread(() -> Toast.makeText(SettingsActivity.this,
                    result == null ? getString(R.string.backup_export_done)
                            : getString(R.string.backup_export_failed, result),
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    private void confirmImport(final Uri uri) {
        new Thread(() -> {
            String error = null;
            BackupManager.Backup backup = null;
            try {
                backup = BackupManager.readBackup(SettingsActivity.this, uri);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final BackupManager.Backup parsed = backup;
            final String failure = error;
            runOnUiThread(() -> {
                if (failure != null || parsed == null) {
                    Toast.makeText(SettingsActivity.this,
                            getString(R.string.backup_import_failed, failure), Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.import_backup)
                        .setMessage(getString(R.string.backup_import_confirm,
                                BackupManager.describe(parsed)))
                        .setPositiveButton(R.string.backup_import_do, (d, w) -> doImport(parsed))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        }).start();
    }

    private void doImport(final BackupManager.Backup backup) {
        new Thread(() -> {
            final int count = BackupManager.restore(SettingsActivity.this, backup);
            runOnUiThread(() -> {
                Toast.makeText(SettingsActivity.this,
                        getString(R.string.backup_import_done, count), Toast.LENGTH_LONG).show();
                // 配置与聊天均已替换，重建界面状态后返回
                setResult(RESULT_OK);
                finish();
            });
        }).start();
    }

    // ===== 供应商列表 =====

    private void renderProviders() {
        providerList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        List<Provider> providers = configManager.getProviders();
        for (Provider p : providers) {
            View row = inflater.inflate(R.layout.item_provider, providerList, false);
            TextView name = row.findViewById(R.id.providerName);
            TextView info = row.findViewById(R.id.providerInfo);
            TextView models = row.findViewById(R.id.providerModels);

            name.setText(p.displayName());
            String url = p.normalizedBaseUrl();
            if (url.length() == 0) {
                info.setText(R.string.provider_not_configured);
            } else if (p.apiKey == null || p.apiKey.length() == 0) {
                info.setText(url + "  ·  " + getString(R.string.provider_no_key));
            } else {
                info.setText(url);
            }
            models.setText(getString(R.string.provider_models_format, p.models == null ? 0 : p.models.size()));

            row.setOnClickListener(v -> showProviderDialog(p));
            providerList.addView(row);
        }
    }

    /** 编辑供应商：保存 / 拉取模型 / 删除 */
    private void showProviderDialog(final Provider provider) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_provider, null, false);
        final EditText editName = content.findViewById(R.id.dialogProviderName);
        final EditText editBaseUrl = content.findViewById(R.id.dialogProviderBaseUrl);
        final EditText editApiKey = content.findViewById(R.id.dialogProviderApiKey);
        final Button btnPasteApiKey = content.findViewById(R.id.btnPasteApiKey);
        final Button btnToggleApiKey = content.findViewById(R.id.btnToggleApiKey);

        editName.setText(provider.name);
        editBaseUrl.setText(provider.baseUrl);
        editApiKey.setText(provider.apiKey);

        // Key 默认遮罩显示；这里用代码施加遮罩，而不是 inputType="textPassword"，
        // 以免触发系统安全键盘（它会禁用粘贴，长 Key 根本没法输入）
        final boolean[] keyVisible = {false};
        applyKeyMask(editApiKey, false);
        btnToggleApiKey.setOnClickListener(v -> {
            keyVisible[0] = !keyVisible[0];
            applyKeyMask(editApiKey, keyVisible[0]);
            btnToggleApiKey.setText(keyVisible[0] ? R.string.hide_key : R.string.show_key);
        });
        btnPasteApiKey.setOnClickListener(v -> pasteFromClipboard(editApiKey));

        new AlertDialog.Builder(this)
                .setTitle(R.string.edit_provider)
                .setView(content)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    configManager.updateProvider(provider.id,
                            editName.getText().toString(),
                            editBaseUrl.getText().toString(),
                            editApiKey.getText().toString());
                    renderProviders();
                })
                .setNeutralButton(R.string.fetch_provider_models, (dialog, which) -> {
                    configManager.updateProvider(provider.id,
                            editName.getText().toString(),
                            editBaseUrl.getText().toString(),
                            editApiKey.getText().toString());
                    renderProviders();
                    fetchModels(provider);
                })
                .setNegativeButton(R.string.delete, (dialog, which) ->
                        confirmDeleteProvider(provider))
                .show();
    }

    /** 用 TransformationMethod 控制遮罩：不影响输入、不触发安全键盘，粘贴照常可用 */
    private void applyKeyMask(EditText editText, boolean visible) {
        editText.setTransformationMethod(visible ? null : PasswordTransformationMethod.getInstance());
        editText.setSelection(editText.getText().length());
    }

    /** 一键粘贴：避免在老设备上长按选择「粘贴」的麻烦 */
    private void pasteFromClipboard(EditText target) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        target.setText(text.toString().trim());
        target.setSelection(target.getText().length());
    }

    private void confirmDeleteProvider(final Provider provider) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_provider_confirm, provider.displayName()))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    configManager.removeProvider(provider.id);
                    selectedDefaultModelId = configManager.getConfig().defaultModelId;
                    renderProviders();
                    updateDefaultModelButton();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void fetchModels(final Provider provider) {
        if (!provider.isValidForFetch()) {
            Toast.makeText(this, R.string.provider_not_configured, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.fetching_models, Toast.LENGTH_SHORT).show();
        apiService.getModels(provider, new ApiCallback<List<ModelDef>>() {
            @Override
            public void onSuccess(List<ModelDef> models) {
                // 拉取后不直接全部添加：弹窗让用户挑选真正要用的模型
                if (models.isEmpty()) {
                    Toast.makeText(SettingsActivity.this,
                            R.string.models_empty, Toast.LENGTH_LONG).show();
                    return;
                }
                showModelPickDialog(provider, models);
            }

            @Override
            public void onError(ApiError error) {
                Toast.makeText(SettingsActivity.this,
                        getString(R.string.fetch_failed_format, error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 拉取结果挑选：已添加的模型默认勾选，新拉取的默认不勾选（按需挑选） */
    private void showModelPickDialog(final Provider provider, final List<ModelDef> fetched) {
        List<String> preselected = new ArrayList<>();
        if (provider.models != null) {
            for (ModelDef m : provider.models) {
                if (m != null) preselected.add(m.uid());
            }
        }
        ModelPickerDialog.show(this,
                getString(R.string.pick_models_title, provider.displayName(), fetched.size()),
                fetched, preselected, new ModelPickerDialog.OnPickListener() {
                    @Override
                    public void onPick(List<ModelDef> selected) {
                        configManager.applySelectedModels(provider.id, selected);
                        renderProviders();
                        Toast.makeText(SettingsActivity.this,
                                getString(R.string.models_added_format, selected.size()),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ===== 默认模型 =====

    private void updateDefaultModelButton() {
        ModelDef model = ModelCatalog.getInstance().getModel(selectedDefaultModelId);
        if (model != null) {
            Provider provider = ModelCatalog.getInstance().findProvider(model.providerId);
            String prefix = provider != null ? provider.displayName() + " · " : "";
            btnDefaultModel.setText(prefix + model.displayName);
        } else if (selectedDefaultModelId != null && selectedDefaultModelId.length() > 0) {
            btnDefaultModel.setText(selectedDefaultModelId);
        } else {
            btnDefaultModel.setText(R.string.model_not_selected);
        }
    }

    private void showModelSelector() {
        ModelSelectorSheet sheet = new ModelSelectorSheet();
        sheet.setListener(model -> {
            selectedDefaultModelId = model.uid(); // 默认模型按 uid 保存，避免同名模型串供应商
            updateDefaultModelButton();
        });
        sheet.show(getSupportFragmentManager(), "model");
    }

    private void save() {
        configManager.updateSystemPrompt(editSystemPrompt.getText().toString());
        configManager.updateNewapiDialect(switchNewapi.isChecked());
        configManager.updateDefaultModel(selectedDefaultModelId);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
