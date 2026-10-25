package io.github.numai5;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;
import io.github.numai5.data.UiSettings;

/**
 * 调整界面：界面大小（整体缩放）与界面边距（百分比）。
 *
 * 首次启动自动打开；之后可从设置页进入。本页**不应用**缩放与边距
 * （固定 1.0 倍显示），这样即使把数值调到极端值，也仍能回到这里恢复默认。
 *
 * 「查看预览」：保存当前值并返回主界面查看效果（不标记已完成）；
 * 「确认设置」：保存并标记已完成，之后启动不再自动弹出。
 */
public class TuneActivity extends AppCompatActivity {

    private EditText editScale;
    private EditText editMarginH;
    private EditText editMarginV;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tune);

        editScale = findViewById(R.id.tuneScale);
        editMarginH = findViewById(R.id.tuneMarginH);
        editMarginV = findViewById(R.id.tuneMarginV);

        findViewById(R.id.tuneBack).setOnClickListener(v -> finish());
        findViewById(R.id.tuneReset).setOnClickListener(v -> fillInputs(1f, 0f, 0f));
        findViewById(R.id.tunePreview).setOnClickListener(v -> preview());
        findViewById(R.id.tuneConfirm).setOnClickListener(v -> apply(true));

        fillInputs(UiSettings.getScale(this), UiSettings.getMarginH(this), UiSettings.getMarginV(this));
    }

    private void fillInputs(float scale, float marginH, float marginV) {
        editScale.setText(format(scale));
        editMarginH.setText(format(marginH));
        editMarginV.setText(format(marginV));
    }

    private String format(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * 读取并校验输入，合法则保存。
     * 缩放超出 0.25~5.0 夹紧并提示；边距超过 30 不保存（该项保持原值）。
     */
    /**
     * 查看预览：保存当前值（不标记已完成），打开主界面看实际效果。
     *
     * 这里不能 finish()：以前预览等于「关掉本页」，而未确认时主界面会再次自动弹回本页，
     * 表现就是「闪一下又回到调整界面」。本页留在返回栈，按返回键即可继续调整。
     */
    private void preview() {
        saveValues();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_PREVIEW_TUNING, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        toast(getString(R.string.tune_preview_hint));
    }

    private void apply(boolean confirm) {
        saveValues();
        if (confirm) {
            float scale = UiSettings.getScale(this);
            if (scale < 0.5f || scale > 2.5f) {
                // 保护：极端倍率下主界面可能难以操作（按钮被缩得看不见/挤出屏幕）。
                // 不标记「已完成」，下次启动会自动再进本页，用户可随时改回来。
                toast(getString(R.string.tune_extreme_warning));
            } else {
                UiSettings.markTuned(this);
                toast(getString(R.string.tune_saved));
            }
        }
        finish();
    }

    /** 校验并保存三个值（预览与确认共用），非法值夹到合法范围并提示 */
    private void saveValues() {
        float scale = parse(editScale, 1f);
        if (scale < UiSettings.SCALE_MIN || scale > UiSettings.SCALE_MAX) {
            scale = UiSettings.clampScale(scale);
            editScale.setText(format(scale));
            toast(getString(R.string.tune_scale_clamped));
        }

        float marginH = parse(editMarginH, 0f);
        float marginV = parse(editMarginV, 0f);
        boolean marginClamped = false;
        if (marginH > UiSettings.MARGIN_MAX) {
            marginH = UiSettings.getMarginH(this); // 超出不保存，保持原值
            editMarginH.setText(format(marginH));
            marginClamped = true;
        }
        if (marginV > UiSettings.MARGIN_MAX) {
            marginV = UiSettings.getMarginV(this);
            editMarginV.setText(format(marginV));
            marginClamped = true;
        }
        if (marginClamped) {
            toast(getString(R.string.tune_margin_clamped));
        }

        UiSettings.save(this, scale, marginH, marginV);
    }

    private float parse(EditText edit, float fallback) {
        String text = edit.getText() == null ? "" : edit.getText().toString().trim();
        if (text.length() == 0) return fallback;
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
