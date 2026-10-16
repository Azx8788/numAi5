package io.github.numai5.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;

/** 本地图片采样解码（消息气泡显示用），避免大图 OOM。 */
public class ImageLoader {

    public static Bitmap load(File file, int maxSize) {
        if (file == null || !file.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSize);
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
    }

    private static int computeInSampleSize(int width, int height, int maxSize) {
        int sampleSize = 1;
        int halfW = width / 2;
        int halfH = height / 2;
        while ((halfW / sampleSize) >= maxSize && (halfH / sampleSize) >= maxSize) {
            sampleSize *= 2;
        }
        return sampleSize;
    }
}
