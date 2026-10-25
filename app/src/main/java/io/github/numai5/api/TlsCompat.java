package io.github.numai5.api;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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

    private static Context appContext;
    private static X509TrustManager trustManager;
    private static boolean initialized;

    private TlsCompat() {
    }

    /** Application.onCreate 里调用一次，提供读取 assets 的 Context */
    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
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
        try {
            String[] files = appContext.getAssets().list(ASSET_DIR);
            if (files == null || files.length == 0) return null;

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            int loaded = 0;
            for (String file : files) {
                if (file == null || !file.toLowerCase(Locale.ROOT).endsWith(".pem")) continue;
                try (InputStream in = appContext.getAssets().open(ASSET_DIR + "/" + file)) {
                    String text = readText(in);
                    loaded += loadPemBundle(keyStore, factory, text, loaded);
                } catch (Exception e) {
                    Log.e(TAG, "load bundled cert failed: " + file, e);
                }
            }
            if (loaded == 0) return null;
            Log.i(TAG, "loaded " + loaded + " bundled root certificates");

            TrustManagerFactory tmFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmFactory.init(keyStore);
            for (TrustManager manager : tmFactory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
            }
        } catch (Exception e) {
            Log.e(TAG, "bundled trust manager unavailable", e);
        }
        return null;
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
