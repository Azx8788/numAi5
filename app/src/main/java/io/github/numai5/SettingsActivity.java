package io.github.numai5;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.switchmaterial.SwitchMaterial;
import io.github.numai5.api.ApiCallback;
import io.github.numai5.api.ApiError;
import io.github.numai5.api.ApiService;
import io.github.numai5.data.ConfigManager;
import io.github.numai5.model.Config;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.ui.ModelSelectorSheet;

import java.util.List;

/**
 * 设置页（M1）：配置中转站 baseUrl / apiKey / 默认模型 / 系统提示词 / NewAPI 方言开关。
 * 必须先配置好 baseUrl 和 key 才能拉取 /models。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText editBaseUrl, editApiKey, editSystemPrompt;
    private SwitchMaterial switchNewapi;
    private Button btnDefaultModel, btnFetchModels, btnSave;
    private ConfigManager configManager;
    private String selectedDefaultModelId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings);

        configManager = ConfigManager.getInstance(this);
        Config config = configManager.getConfig();

        editBaseUrl = findViewById(R.id.editBaseUrl);
        editApiKey = findViewById(R.id.editApiKey);
        editSystemPrompt = findViewById(R.id.editSystemPrompt);
        switchNewapi = findViewById(R.id.switchNewapi);
        btnDefaultModel = findViewById(R.id.btnDefaultModel);
        btnFetchModels = findViewById(R.id.btnFetchModels);
        btnSave = findViewById(R.id.btnSave);

        editBaseUrl.setText(config.baseUrl);
        editApiKey.setText(config.apiKey);
        editSystemPrompt.setText(config.systemPrompt);
        switchNewapi.setChecked(config.newapiDialect);
        selectedDefaultModelId = config.defaultModelId;
        updateDefaultModelButton();

        btnDefaultModel.setOnClickListener(v -> showModelSelector());
        btnFetchModels.setOnClickListener(v -> fetchModels());
        btnSave.setOnClickListener(v -> save());
    }

    private void updateDefaultModelButton() {
        ModelDef model = ModelCatalog.getInstance().getModel(selectedDefaultModelId);
        if (model != null) {
            btnDefaultModel.setText(model.displayName);
        } else if (selectedDefaultModelId != null && selectedDefaultModelId.length() > 0) {
            btnDefaultModel.setText(selectedDefaultModelId);
        } else {
            btnDefaultModel.setText("未选择");
        }
    }

    private void showModelSelector() {
        // 先保存 baseUrl/key，拉取模型需要
        saveConfigOnly();
        ModelSelectorSheet sheet = new ModelSelectorSheet();
        sheet.setListener(model -> {
            selectedDefaultModelId = model.id;
            updateDefaultModelButton();
        });
        sheet.show(getSupportFragmentManager(), "model");
    }

    private void fetchModels() {
        saveConfigOnly();
        if (!configManager.isValid()) {
            Toast.makeText(this, R.string.config_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        btnFetchModels.setEnabled(false);
        new ApiService(this).getModels(new ApiCallback<List<ModelDef>>() {
            @Override
            public void onSuccess(List<ModelDef> models) {
                btnFetchModels.setEnabled(true);
                Toast.makeText(SettingsActivity.this,
                        "已拉取 " + models.size() + " 个模型", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onError(ApiError error) {
                btnFetchModels.setEnabled(true);
                Toast.makeText(SettingsActivity.this,
                        "拉取失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void saveConfigOnly() {
        configManager.updateBaseUrl(editBaseUrl.getText().toString().trim());
        configManager.updateApiKey(editApiKey.getText().toString().trim());
        configManager.updateSystemPrompt(editSystemPrompt.getText().toString());
        configManager.updateNewapiDialect(switchNewapi.isChecked());
    }

    private void save() {
        saveConfigOnly();
        configManager.updateDefaultModel(selectedDefaultModelId);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
