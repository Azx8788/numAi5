package io.github.numai5;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.util.List;
import io.github.numai5.ui.ZoomableImageView;
import io.github.numai5.vision.ImageSaver;

/**
 * 全屏图片预览：单图缩放/拖动/双击复位、保存到相册。
 *
 * 低开销设计：只保留一张图（ViewPager 依赖已撤），多图场景由调用方分别传入。
 */
public class ImagePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_FILES = "files";
    public static final String EXTRA_INDEX = "index";

    private static final int REQ_WRITE = 100;

    private String filePath;


    // ===== 全局界面适配（界面大小 / 边距） =====

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(io.github.numai5.data.UiSettings.wrap(newBase));
    }

    @Override
    protected void onPostCreate(android.os.Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        io.github.numai5.data.UiSettings.applyMargins(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        io.github.numai5.data.UiSettings.checkGeneration(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_preview);

        List<String> list = getIntent().getStringArrayListExtra(EXTRA_FILES);
        if (list == null || list.isEmpty()) {
            finish();
            return;
        }
        int index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (index < 0 || index >= list.size()) index = 0;
        filePath = list.get(index);

        ZoomableImageView image = findViewById(R.id.previewImage);
        TextView close = findViewById(R.id.previewClose);
        Button save = findViewById(R.id.previewSave);

        try {
            image.setImageBitmap(BitmapFactory.decodeFile(filePath));
        } catch (Throwable t) {
            // 图片损坏：显示空白不崩溃
        }

        close.setOnClickListener(v -> finish());
        save.setOnClickListener(v -> save());
    }

    private void save() {
        File src = new File(filePath);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        doSave(src);
    }

    private void doSave(File src) {
        String error = ImageSaver.saveToGallery(this, src);
        Toast.makeText(this, error == null
                        ? getString(R.string.preview_saved) : error,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doSave(new File(filePath));
        }
    }
}
