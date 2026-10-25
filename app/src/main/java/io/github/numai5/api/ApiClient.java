package io.github.numai5.api;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import io.github.numai5.data.ProviderRateLimiter;
import io.github.numai5.provider.Provider;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OkHttp 封装（B4 超时放宽 + 错误解析 + 供应商级调度）。
 *
 * <p>模型列表请求（含魔搭 POST→GET 的方法回退）每次实际发送都经过
 * {@link ProviderRateLimiter}，与聊天、生图共享同一供应商的并发、间隔、RPM 与冷却。
 * 调用方通过 {@link ApiRequest#setProvider} 提供供应商；未提供时退化为默认队列。
 *
 * Android 5 (API 21) 默认已启用 TLS 1.2，无需自定义 SSLSocketFactory。
 */
public class ApiClient {
    private static final String TAG = "ApiClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;

    public ApiClient() {
        this.client = TlsCompat.configure(new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true))
                .build();
    }

    public ApiResponse execute(ApiRequest apiRequest) throws ApiError {
        Provider provider = apiRequest.getProvider();
        String url = apiRequest.getBaseUrl() + apiRequest.getEndpoint();
        Request.Builder builder = new Request.Builder().url(url);
        // 已知会对客户端做指纹校验的站点（如 agentrouter.org），自动补上兼容头
        ApiCompatHeaders.apply(builder, url);

        String apiKey = apiRequest.getApiKey();
        if (apiKey != null && apiKey.length() > 0) {
            // 清洗：用户可能把说明文字/换行/引号一起粘进 Key，OkHttp 会直接抛异常
            builder.header("Authorization", "Bearer " + ApiAuth.sanitize(apiKey));
        }
        for (Map.Entry<String, String> entry : apiRequest.getHeaders().entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        String method = apiRequest.getMethod();
        if ("GET".equalsIgnoreCase(method)) {
            builder.get();
        } else {
            String body = apiRequest.getBody();
            RequestBody requestBody = RequestBody.create(body == null ? "" : body, JSON);
            builder.post(requestBody);
        }

        // 动态读超时：按请求指定的 timeout 重新 build client（极少开销）
        OkHttpClient callClient = client.newBuilder()
                .readTimeout(apiRequest.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();

        // 调度：每次实际发送（含方法回退）单独占一次额度
        ProviderRateLimiter.Entry entry =
                ProviderRateLimiter.submit(provider, ProviderRateLimiter.RequestType.MODEL_LIST);
        ProviderRateLimiter.Denial denial = ProviderRateLimiter.awaitTurn(entry);
        if (denial != null) {
            throw RateLimitPolicy.denialError(denial);
        }
        if (!ProviderRateLimiter.markRunning(entry)) {
            ProviderRateLimiter.finish(entry, ProviderRateLimiter.State.CANCELLED, null);
            throw new ApiError("请求已取消");
        }

        Call call = callClient.newCall(builder.build());
        ProviderRateLimiter.bind(entry, call::cancel);
        ProviderRateLimiter.State terminal = ProviderRateLimiter.State.FAILED;
        try (Response response = call.execute()) {
            int code = response.code();
            ResponseBody body = response.body();
            String bodyString = body != null ? body.string() : "";

            if (code >= 200 && code < 300) {
                terminal = ProviderRateLimiter.State.COMPLETED;
                return new ApiResponse(code, bodyString, null);
            }
            String retryAfter = response.header("Retry-After");
            ApiError error = ErrorMapper.fromHttp(code, bodyString, retryAfter);
            RateLimitPolicy.report(provider, error);
            throw error;
        } catch (ApiError e) {
            throw e;
        } catch (java.net.SocketTimeoutException | java.net.SocketException e) {
            throw new ApiError(e.getMessage(), true);
        } catch (IOException e) {
            throw new ApiError(e.getMessage(), false);
        } finally {
            ProviderRateLimiter.finish(entry, terminal, null);
        }
    }
}
