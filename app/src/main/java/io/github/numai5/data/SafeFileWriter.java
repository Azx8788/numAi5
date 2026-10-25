package io.github.numai5.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 崩溃安全的文件写入（步骤：内存记忆与聊天归档共用）。
 *
 * 写入顺序：同目录 .tmp -> flush -> fsync -> 可选备份旧文件为 .bak -> rename。
 * rename 在同一目录内完成，正常情况下是原子的；rename 失败时保留原文件并抛异常，
 * 不做“先删再写”的降级，避免断电留下半个文件。
 */
public final class SafeFileWriter {

    private SafeFileWriter() {
    }

    public static void write(File target, byte[] data) throws IOException {
        write(target, data, false);
    }

    /**
     * @param backup 是否把旧文件复制为 .bak。聊天记录文件较大，调用方可按次数抽样备份；
     *               记忆文件很小，可以每次备份。
     */
    public static void write(File target, byte[] data, boolean backup) throws IOException {
        if (target == null) throw new IOException("target is null");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent.getAbsolutePath());
        }

        File tmp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            fos.write(data);
            fos.flush();
            fos.getFD().sync();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }

        if (backup && target.exists()) {
            try {
                copy(target, new File(target.getAbsolutePath() + ".bak"));
            } catch (IOException ignored) {
                // 备份失败不阻断主写入；主文件仍按 tmp + rename 保证完整。
            }
        }

        if (!tmp.renameTo(target)) {
            // 保留 tmp 便于排查，但目标文件保持原样。
            throw new IOException("rename failed: " + tmp.getAbsolutePath());
        }
    }

    public static byte[] read(File target) throws IOException {
        if (target == null || !target.exists()) throw new IOException("file not found: " + target);
        long length = target.length();
        if (length > Integer.MAX_VALUE) throw new IOException("file too large");
        byte[] bytes = new byte[(int) length];
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(target);
            int offset = 0;
            while (offset < bytes.length) {
                int read = fis.read(bytes, offset, bytes.length - offset);
                if (read <= 0) break;
                offset += read;
            }
            if (offset != bytes.length) {
                throw new IOException("short read: " + offset + "/" + bytes.length);
            }
            return bytes;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 主文件读失败时回退 .bak；两者都不行时抛原始异常。 */
    public static byte[] readWithBackup(File target) throws IOException {
        try {
            return read(target);
        } catch (IOException first) {
            File bak = new File(target.getAbsolutePath() + ".bak");
            if (bak.exists()) {
                try {
                    return read(bak);
                } catch (IOException second) {
                    second.addSuppressed(first);
                    throw second;
                }
            }
            throw first;
        }
    }

    /** 删除主文件、临时文件与备份；用于用户主动删除记忆。 */
    public static void delete(File target) {
        if (target == null) return;
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(target.getAbsolutePath() + ".tmp").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(target.getAbsolutePath() + ".bak").delete();
    }

    private static void copy(File from, File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent.getAbsolutePath());
        }
        FileOutputStream out = null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}