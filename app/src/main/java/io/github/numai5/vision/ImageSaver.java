package io.github.numai5.vision;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把本地图片文件保存到系统相册。
 * - Android 10+（API 29）：MediaStore，无需权限
 * - Android 9-（API 28 及以下）：存到公共 Pictures 目录，需要 WRITE_EXTERNAL_STORAGE 权限（调用方负责申请）
 */
public final class ImageSaver {

    /** 保存成功返回 null，失败返回错误文案 */
    public static String saveToGallery(Context context, File src) {
        if (src == null || !src.exists()) return "图片文件不存在";

        String displayName = src.getName();
        String mime = "image/png";
        if (displayName.endsWith(".jpg") || displayName.endsWith(".jpeg")) mime = "image/jpeg";
        else if (displayName.endsWith(".webp")) mime = "image/webp";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
                values.put(MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/NumAi5");
                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return "相册写入失败";
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out == null) return "相册写入失败";
                    copy(in, out);
                }
                return null;
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "NumAi5");
                if (!dir.exists() && !dir.mkdirs()) return "无法创建相册目录";
                File target = new File(dir, displayName);
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new FileOutputStream(target)) {
                    copy(in, out);
                }
                return null;
            }
        } catch (Exception e) {
            return "保存失败：" + e.getMessage();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private ImageSaver() {
    }
}
