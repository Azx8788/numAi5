package io.github.numai5.vision;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 图片选择（A5 / 步骤13-14）：
 * - 相册：ACTION_PICK + content:// URI（无需存储权限）；
 * - 拍照：ACTION_IMAGE_CAPTURE + EXTRA_OUTPUT 全尺寸输出（FileProvider 共享 filesDir/images），
 *   Android 6+ 运行时申请 CAMERA 权限，授权后由 Activity 重新调用 takePhoto；
 * - 两条路径统一走「inJustDecodeBounds 测尺寸 → inSampleSize 降采样」两步解码，
 *   目标不超过 ImageCompressor.MAX_SIZE，避免大图 OOM，再交 ImageCompressor 压缩保存。
 */
public class ImagePicker {
    private static final String TAG = "ImagePicker";
    public static final int REQ_PICK = 100;
    public static final int REQ_CAMERA = 101;
    public static final int PERM_CAMERA = 102;

    /** 当前拍照目标文件（等待相机应用写入；仅内存记录，不持久化） */
    private static File pendingCameraFile;

    public static void pickImage(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        activity.startActivityForResult(intent, REQ_PICK);
    }

    public static void takePhoto(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.CAMERA}, PERM_CAMERA);
            return;
        }
        try {
            File dir = ImageCompressor.getImagesDir(activity);
            File out = new File(dir, "cam_" + UUID.randomUUID().toString() + ".jpg");
            Uri uri = FileProvider.getUriForFile(activity, authority(activity), out);
            pendingCameraFile = out;
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception e) {
            Log.e(TAG, "take photo failed", e);
            pendingCameraFile = null;
        }
    }

    /** 在 Activity.onActivityResult 中调用，返回本地保存的图片文件名（不含路径） */
    public static String onActivityResult(Context context, int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == REQ_CAMERA && pendingCameraFile != null) {
                pendingCameraFile.delete(); // 用户取消拍照，清理目标临时文件
                pendingCameraFile = null;
            }
            return null;
        }

        Uri uri = null;
        File tempFile = null;
        if (requestCode == REQ_PICK) {
            uri = (data != null) ? data.getData() : null;
            if (uri == null) return null;
        } else if (requestCode == REQ_CAMERA) {
            tempFile = pendingCameraFile;
            pendingCameraFile = null;
            if (tempFile == null || !tempFile.exists()) return null;
            try {
                uri = FileProvider.getUriForFile(context, authority(context), tempFile);
            } catch (Exception e) {
                Log.e(TAG, "camera uri failed", e);
                tempFile.delete();
                return null;
            }
        } else {
            return null;
        }

        Bitmap bitmap = decodeSampled(context.getContentResolver(), uri, ImageCompressor.MAX_SIZE);
        if (bitmap == null) {
            if (tempFile != null) tempFile.delete();
            return null;
        }

        String filename = UUID.randomUUID().toString() + ".jpg";
        File saved = ImageCompressor.compressAndSave(context, bitmap, filename);
        if (!bitmap.isRecycled()) bitmap.recycle();
        if (tempFile != null && (saved == null || !tempFile.equals(saved))) {
            tempFile.delete(); // 清理拍照临时文件（压缩产物使用独立文件名）
        }
        return saved != null ? filename : null;
    }

    /** 步骤14：两步采样解码（先测尺寸、再按 inSampleSize 降采样），目标不超过 maxSize */
    private static Bitmap decodeSampled(ContentResolver resolver, Uri uri, int maxSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream is = resolver.openInputStream(uri);
            if (is == null) return null;
            try {
                BitmapFactory.decodeStream(is, null, bounds);
            } finally {
                closeQuietly(is);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSize);
            InputStream is2 = resolver.openInputStream(uri);
            if (is2 == null) return null;
            try {
                return BitmapFactory.decodeStream(is2, null, opts);
            } finally {
                closeQuietly(is2);
            }
        } catch (Exception e) {
            Log.e(TAG, "decode image failed", e);
            return null;
        }
    }

    /** 官方推荐公式：按 2 的幂降采样，保证结果不低于目标尺寸（随后由 ImageCompressor 精确缩放到上限） */
    private static int computeInSampleSize(int width, int height, int maxSize) {
        int sampleSize = 1;
        int halfW = width / 2;
        int halfH = height / 2;
        while ((halfW / sampleSize) >= maxSize && (halfH / sampleSize) >= maxSize) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static String authority(Context context) {
        return context.getPackageName() + ".fileprovider";
    }

    private static void closeQuietly(InputStream is) {
        try {
            is.close();
        } catch (IOException ignored) {
        }
    }
}
