package io.github.numai5;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import io.github.numai5.api.ApiCallback;
import io.github.numai5.api.ApiError;
import io.github.numai5.api.ApiService;
import io.github.numai5.data.BackupManager;
import io.github.numai5.data.ConfigManager;
import io.github.numai5.model.Config;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;
import io.github.numai5.ui.ModelSelectorSheet;

/**
 * 设置页：多供应商管理（增删改，不限个数）+ 默认模型 + 系统提示词 + NewAPI 方言开关。
 * 每个供应商独立配置名称 / 中转地址 / API Key / 模型列表。
 */
public class SettingsActivity extends AppCompatActivity {

    private LinearLayout providerList;
    private Button btnAddProvider, btnDefaultModel, btnSave, btnExportBackup, btnImportBackup;
    private EditText editSystemPrompt;
    private SwitchMaterial switchNewapi;
    private ConfigManager configManager;
    private String selectedDefaultModelId;

    private static final int REQ_EXPORT = 201;
    private static final int REQ_IMPORT = 202;

    private final ApiService apiService = new ApiService(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings);

        configManager = ConfigManager.getInstance(this);
        Config config = configManager.getConfig();

        providerList = findViewById(R.id.providerList);
        btnAddProvider = findViewById(R.id.btnAddProvider);
        btnDefaultModel = findViewById(R.id.btnDefaultModel);
        btnSave = findViewById(R.id.btnSave);
        btnExportBackup = findViewById(R.id.btnExportBackup);
        btnImportBackup = findViewById(R.id.btnImportBackup);
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

        editName.setText(provider.name);
        editBaseUrl.setText(provider.baseUrl);
        editApiKey.setText(provider.apiKey);

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
                renderProviders();
                Toast.makeText(SettingsActivity.this,
                        getString(R.string.models_fetched_format, models.size()), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(ApiError error) {
                Toast.makeText(SettingsActivity.this,
                        getString(R.string.fetch_failed_format, error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ===== 默认模型 =====

    private void updateDefaultModelButton() {
        ModelDef model = ModelCatalog.getInstance().getModel(selectedDefaultModelId);
        if (model != null) {
            Provider provider = ModelCatalog.getInstance().findProviderOfModel(model.id);
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
            selectedDefaultModelId = model.id;
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
