package io.github.numai5.vision;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * 图片选择（A5）：相册使用 ACTION_PICK + content:// URI（无需存储权限）；
 * 拍照在 Android 6+ 设备上运行时申请 CAMERA 权限。
 */
public class ImagePicker {
    private static final String TAG = "ImagePicker";
    public static final int REQ_PICK = 100;
    public static final int REQ_CAMERA = 101;
    public static final int PERM_CAMERA = 102;

    public static void pickImage(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        activity.startActivityForResult(intent, REQ_PICK);
    }

    public static void takePhoto(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.CAMERA}, PERM_CAMERA);
                return;
            }
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        activity.startActivityForResult(intent, REQ_CAMERA);
    }

    /** 在 Activity.onActivityResult 中调用，返回本地保存的图片文件名（不含路径） */
    public static String onActivityResult(Context context, int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return null;
        Bitmap bitmap = null;
        if (requestCode == REQ_PICK) {
            Uri uri = data.getData();
            if (uri == null) return null;
            try {
                bitmap = android.provider.MediaStore.Images.Media.getBitmap(context.getContentResolver(), uri);
            } catch (IOException e) {
                Log.e(TAG, "load pick image", e);
            }
        } else if (requestCode == REQ_CAMERA) {
            bitmap = (Bitmap) data.getExtras().get("data");
        }
        if (bitmap == null) return null;

        String filename = UUID.randomUUID().toString() + ".jpg";
        File saved = ImageCompressor.compressAndSave(context, bitmap, filename);
        if (!bitmap.isRecycled()) bitmap.recycle();
        return saved != null ? filename : null;
    }
}
