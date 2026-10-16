package io.github.numai5.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 视觉图片压缩与本地存储（A6）。
 * - 最大 1080×1080，JPEG 80%
 * - base64 必须 NO_WRAP（C 类）
 * - 原图存 filesDir/images/<filename>.jpg，持久化只存 filename
 */
public class ImageCompressor {
    private static final String TAG = "ImageCompressor";
    /** 采样解码与压缩共同的目标上限（步骤14） */
    public static final int MAX_SIZE = 1080;
    private static final int JPEG_QUALITY = 80;

    public static File getImagesDir(Context context) {
        File dir = new File(context.getFilesDir(), "images");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File compressAndSave(Context context, Bitmap src, String filename) {
        File dir = getImagesDir(context);
        File out = new File(dir, filename);
        Bitmap scaled = scale(src, MAX_SIZE);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
        } catch (IOException e) {
            Log.e(TAG, "save image failed", e);
            return null;
        }
        if (!scaled.equals(src)) scaled.recycle();
        return out;
    }

    public static String toBase64(File file) {
        if (file == null || !file.exists()) return null;
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int read = fis.read(bytes);
            if (read <= 0) return null;
        } catch (IOException e) {
            Log.e(TAG, "read image failed", e);
            return null;
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    public static Bitmap scale(Bitmap src, int maxSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxSize && h <= maxSize) return src;
        float scale = Math.min((float) maxSize / w, (float) maxSize / h);
        int newW = Math.round(w * scale);
        int newH = Math.round(h * scale);
        return Bitmap.createScaledBitmap(src, newW, newH, true);
    }

    /** 生图结果落盘（PNG，保真不二次有损压缩）；返回文件名 */
    public static String saveGeneratedImage(Context context, byte[] data) {
        if (data == null || data.length == 0) return null;
        String filename = "gen_" + java.util.UUID.randomUUID().toString() + ".png";
        File out = new File(getImagesDir(context), filename);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(data);
            return filename;
        } catch (IOException e) {
            Log.e(TAG, "save generated image failed", e);
            return null;
        }
    }

    public static File imageFile(Context context, String filename) {
        if (filename == null) return null;
        return new File(getImagesDir(context), filename);
    }
}
