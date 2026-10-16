package io.github.numai5;

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
import java.util.List;
import io.github.numai5.api.ApiCallback;
import io.github.numai5.api.ApiError;
import io.github.numai5.api.ApiService;
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
    private Button btnAddProvider, btnDefaultModel, btnSave;
    private EditText editSystemPrompt;
    private SwitchMaterial switchNewapi;
    private ConfigManager configManager;
    private String selectedDefaultModelId;

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
