package io.github.numai5.api;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

/**
 * Android 5 的根证书库停留在 2014 年，缺少 ISRG Root X1/X2 等新根证书。
 * 访问使用 Let's Encrypt 证书的站点（例如 arityflow.top）会报：
 * {@code Trust anchor for certification path not found}。
 *
 * <p>解决办法：把 curl 维护的 Mozilla 公共根证书包（cacert.pem，约 118 张根证书）
 * 随 APK 打包到 assets/certs，与系统信任库组成复合 TrustManager：
 * <ul>
 *   <li>先按系统信任库校验（保持原有行为）；</li>
 *   <li>只有系统报「找不到信任锚」时才用内置根再校验一次。</li>
 * </ul>
 *
 * <p><b>不做「信任所有证书」</b>：那等于关闭 HTTPS 校验，
 * 公共 Wi-Fi / 代理可以中间人劫持并拿到 API Key。这里的签名链、有效期、
 * 主机名校验全部照常执行，只是补上了老系统缺失的根证书。
 */
public final class TlsCompat {
    private static final String TAG = "TlsCompat";
    private static final String ASSET_DIR = "certs";
    /** 应用私有目录的证书包子目录 */
    private static final String CA_DIR = "ca";

    private static Context appContext;
    private static X509TrustManager trustManager;
    private static boolean initialized;
    /** 证书包状态描述，格式：118 张 / 2026-10-20 / 内置 */
    private static String bundleInfo;
    /** 上次导入失败的原因 */
    private static volatile String lastError;

    private TlsCompat() {
    }

    /** Application.onCreate 里调用一次，提供读取 assets 的 Context */
    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
    }

    /**
     * 后台预热：提前解析内置证书包。
     * 118 张证书首次解析约 800 毫秒（桌面 JVM 实测，老设备更慢），
     * 若留给首个网络请求或首次进入设置页，会在主线程造成可见卡顿。
     */
    public static void warmUp() {
        try {
            trustManager();
        } catch (Exception e) {
            Log.e(TAG, "warmUp failed", e);
        }
    }

    /**
     * 供界面显示证书包状态，格式：118 张 / 2026-10-20 / 内置。
     * 不触发加载，未就绪时返回「加载中」。
     */
    public static String describe() {
        synchronized (TlsCompat.class) {
            return bundleInfo == null ? "加载中" : bundleInfo;
        }
    }

    /** 给 OkHttpClient.Builder 装上「系统 + 内置根」的 SSL 校验 */
    public static OkHttpClient.Builder configure(OkHttpClient.Builder builder) {
        if (builder == null) return null;
        try {
            X509TrustManager manager = trustManager();
            if (manager == null) return builder;
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{manager}, new SecureRandom());
            return builder.sslSocketFactory(sslContext.getSocketFactory(), manager);
        } catch (Exception e) {
            // 配置失败就退回系统默认校验，绝不降级为信任所有证书
            Log.e(TAG, "configure tls failed, fallback to system default", e);
            return builder;
        }
    }

    private static synchronized X509TrustManager trustManager() throws Exception {
        if (initialized) return trustManager;
        initialized = true;
        X509TrustManager system = systemTrustManager();
        X509TrustManager bundled = bundledTrustManager();
        if (bundled == null) {
            trustManager = system;
        } else if (system == null) {
            trustManager = bundled;
        } else {
            trustManager = new CompositeTrustManager(system, bundled);
        }
        return trustManager;
    }

    private static X509TrustManager systemTrustManager() {
        try {
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
            }
        } catch (Exception e) {
            Log.e(TAG, "system trust manager unavailable", e);
        }
        return null;
    }

    /**
     * 读取 assets/certs 下的 PEM 文件（单个证书或整包 cacert.pem），
     * 构建只包含内置根证书的 TrustManager；没有资源时返回 null。
     */
    private static X509TrustManager bundledTrustManager() {
        if (appContext == null) return null;
        // 1. 优先用应用私有目录的导入包
        File imported = new File(appContext.getFilesDir(), CA_DIR + "/cacert.pem");
        if (imported.isFile()) {
            String text = safeReadFile(imported);
            if (text != null) {
                X509TrustManager tm = buildFromPem(text, "导入");
                if (tm != null) return tm;
                Log.e(TAG, "imported bundle invalid, fallback to assets");
            }
        }
        // 2. 回退 assets 内置包
        try {
            String[] files = appContext.getAssets().list(ASSET_DIR);
            if (files == null || files.length == 0) return null;
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            int loaded = 0;
            String dataDate = null;
            for (String file : files) {
                if (file == null || !file.toLowerCase(Locale.ROOT).endsWith(".pem")) continue;
                try (InputStream in = appContext.getAssets().open(ASSET_DIR + "/" + file)) {
                    String text = readText(in);
                    if (dataDate == null) dataDate = pemDataDate(text);
                    loaded += loadPemBundle(keyStore, factory, text, loaded);
                } catch (Exception e) {
                    Log.e(TAG, "load bundled cert failed: " + file, e);
                }
            }
            if (loaded == 0) return null;
            Log.i(TAG, "loaded " + loaded + " bundled root certificates");
            bundleInfo = loaded + " 张 / " + (dataDate == null ? "日期未知" : dataDate) + " / 内置";
            return trustManagerFromKeyStore(keyStore);
        } catch (Exception e) {
            Log.e(TAG, "bundled trust manager unavailable", e);
        }
        return null;
    }

    /** 从 PEM 文本构建信任管理器，失败返回 null；附带更新 bundleInfo */
    private static X509TrustManager buildFromPem(String text, String source) {
        if (text == null) return null;
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            int loaded = loadPemBundle(keyStore, factory, text, 0);
            if (loaded == 0) return null;
            String date = pemDataDate(text);
            bundleInfo = loaded + " 张 / " + (date == null ? "日期未知" : date) + " / " + source;
            Log.i(TAG, "loaded " + loaded + " certs from " + source);
            return trustManagerFromKeyStore(keyStore);
        } catch (Exception e) {
            Log.e(TAG, "build from " + source + " failed", e);
            return null;
        }
    }

    private static X509TrustManager trustManagerFromKeyStore(KeyStore keyStore) {
        try {
            TrustManagerFactory tmFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmFactory.init(keyStore);
            for (TrustManager manager : tmFactory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
            }
        } catch (Exception e) {
            Log.e(TAG, "trust manager from keystore failed", e);
        }
        return null;
    }

    /** 读取文件全部内容为字符串，失败返回 null */
    private static String safeReadFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return readText(in);
        } catch (Exception e) {
            Log.e(TAG, "read file failed: " + file, e);
            return null;
        }
    }

    /**
     * 导入外部证书包：校验后写入 filesDir/ca/cacert.pem 并重置缓存。
     * 校验规则：证书数不少于 60，必须含 ISRG Root X1。
     * 写入用临时文件 + rename，保证原子；已创建的客户端重启后才完全生效。
     * 返回 true 表示成功，失败原因见 {@link #lastImportError()}。
     */
    public static boolean importBundle(Context context, byte[] pemBytes) {
        if (context == null || pemBytes == null || pemBytes.length == 0) {
            lastError = "数据为空";
            return false;
        }
        try {
            String text = new String(pemBytes, "UTF-8");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            int loaded = loadPemBundle(ks, factory, text, 0);
            if (loaded < 60) {
                lastError = "证书数量异常：" + loaded + "（要求不少于 60）";
                return false;
            }
            if (!containsIsrgRootX1(ks)) {
                lastError = "缺少 ISRG Root X1";
                return false;
            }
            File dir = new File(context.getFilesDir(), CA_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                lastError = "无法创建目录：" + dir;
                return false;
            }
            File tmp = new File(dir, "cacert.pem.tmp");
            File target = new File(dir, "cacert.pem");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(pemBytes);
                out.flush();
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                lastError = "无法替换旧证书包";
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(target)) {
                lastError = "写入失败";
                tmp.delete();
                return false;
            }
            synchronized (TlsCompat.class) {
                trustManager = null;
                initialized = false;
            }
            // 立即预热，刷新 describe 显示
            warmUp();
            return true;
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.toString();
            return false;
        }
    }

    /** 上次导入失败的原因，成功时为 null */
    public static String lastImportError() {
        return lastError;
    }

    private static boolean containsIsrgRootX1(KeyStore ks) throws Exception {
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            X509Certificate c = (X509Certificate) ks.getCertificate(aliases.nextElement());
            if (c != null && c.getSubjectX500Principal().getName().contains("ISRG Root X1")) {
                return true;
            }
        }
        return false;
    }

    /** 读取整个 PEM 文本（cacert.pem 约 200KB，只读一次） */
    private static String readText(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    /**
     * 从 cacert.pem 头部读取 Mozilla 数据日期，转为 yyyy-MM-dd。
     * 单个证书的 PEM 没有这段注释，返回 null。
     */
    private static String pemDataDate(String text) {
        final String key = "Certificate data from Mozilla as of:";
        int start = text.indexOf(key);
        if (start < 0) return null;
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        String raw = text.substring(start + key.length(), end).trim();
        try {
            Date date = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy zzz", Locale.US).parse(raw);
            if (date != null) return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date);
        } catch (Exception ignored) {
            // 格式变化时退回原始字符串，不影响证书加载
        }
        return raw;
    }

    /**
     * 从 PEM 文本里逐个提取证书。整包 cacert.pem 里有多张证书，
     * CertificateFactory.generateCertificate 只会读第一张，所以这里手动切块。
     */
    private static int loadPemBundle(KeyStore keyStore, CertificateFactory factory,
                                     String text, int offset) {
        final String begin = "-----BEGIN CERTIFICATE-----";
        final String end = "-----END CERTIFICATE-----";
        int count = 0;
        int from = 0;
        while (true) {
            int start = text.indexOf(begin, from);
            if (start < 0) break;
            int stop = text.indexOf(end, start);
            if (stop < 0) break;
            try {
                String base64 = text.substring(start + begin.length(), stop)
                        .replaceAll("\\s+", "");
                byte[] der = Base64.decode(base64, Base64.DEFAULT);
                X509Certificate cert = (X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(der));
                keyStore.setCertificateEntry("bundled-" + (offset + count), cert);
                count++;
            } catch (Exception e) {
                // 个别证书解析失败只跳过该条，不影响整包
                Log.e(TAG, "skip broken pem block", e);
            }
            from = stop + end.length();
        }
        return count;
    }

    /**
     * 系统校验优先；系统失败时再试内置根。
     * 两条路径都失败则抛出内置根的异常（保留系统异常作为 suppressed，便于排障）。
     */
    private static final class CompositeTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager bundled;

        CompositeTrustManager(X509TrustManager system, X509TrustManager bundled) {
            this.system = system;
            this.bundled = bundled;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkClientTrusted(chain, authType);
            } catch (CertificateException e) {
                bundled.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException systemError) {
                try {
                    bundled.checkServerTrusted(chain, authType);
                } catch (CertificateException bundledError) {
                    bundledError.addSuppressed(systemError);
                    throw bundledError;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = system.getAcceptedIssuers();
            X509Certificate[] b = bundled.getAcceptedIssuers();
            if (a == null || a.length == 0) return b == null ? new X509Certificate[0] : b;
            if (b == null || b.length == 0) return a;
            X509Certificate[] all = new X509Certificate[a.length + b.length];
            System.arraycopy(a, 0, all, 0, a.length);
            System.arraycopy(b, 0, all, a.length, b.length);
            return all;
        }
    }
}
