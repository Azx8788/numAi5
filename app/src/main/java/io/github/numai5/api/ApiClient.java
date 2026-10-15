package io.github.numai5.api;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OkHttp 封装（B4 超时放宽 + 错误解析）。
 * Android 5 (API 21) 默认已启用 TLS 1.2，无需自定义 SSLSocketFactory。
 */
public class ApiClient {
    private static final String TAG = "ApiClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;

    public ApiClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public ApiResponse execute(ApiRequest apiRequest) throws ApiError {
        String url = apiRequest.getBaseUrl() + apiRequest.getEndpoint();
        Request.Builder builder = new Request.Builder().url(url);

        String apiKey = apiRequest.getApiKey();
        if (apiKey != null && apiKey.length() > 0) {
            builder.header("Authorization", "Bearer " + apiKey);
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

        try (Response response = callClient.newCall(builder.build()).execute()) {
            int code = response.code();
            ResponseBody body = response.body();
            String bodyString = body != null ? body.string() : "";

            if (code >= 200 && code < 300) {
                return new ApiResponse(code, bodyString, null);
            } else {
                String errorMsg = extractErrorMessage(bodyString);
                throw new ApiError(errorMsg != null ? errorMsg : ("HTTP " + code),
                        false, bodyString);
            }
        } catch (java.net.SocketTimeoutException | java.net.SocketException e) {
            throw new ApiError(e.getMessage(), true);
        } catch (IOException e) {
            throw new ApiError(e.getMessage(), false);
        }
    }

    /** 解析 {"error":{"message":"..."}}（C 类：错误透出到气泡） */
    private String extractErrorMessage(String body) {
        if (body == null || body.length() == 0) return null;
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("error")) {
                com.google.gson.JsonElement err = obj.get("error");
                if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
                    return err.getAsJsonObject().get("message").getAsString();
                }
                if (err.isJsonPrimitive()) return err.getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
