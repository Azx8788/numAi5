package io.github.numai5.api;

import java.util.HashMap;
import java.util.Map;

import io.github.numai5.provider.Provider;

public class ApiRequest {
    private String endpoint;
    private String method;
    private String baseUrl;
    private String apiKey;
    private String body;
    /** 所属供应商：模型列表等请求按供应商排队限流（可为 null，退化为默认队列） */
    private Provider provider;
    private final Map<String, String> headers = new HashMap<>();
    private int readTimeoutMs = 300_000; // 默认 5 分钟（B4）

    public ApiRequest(String endpoint, String method) {
        this.endpoint = endpoint;
        this.method = method;
    }

    public ApiRequest setCredentials(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        return this;
    }

    /** 绑定供应商：让该请求进入该供应商的统一调度队列 */
    public ApiRequest setProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    public ApiRequest setBody(String body) {
        this.body = body;
        return this;
    }

    public ApiRequest addHeader(String key, String value) {
        headers.put(key, value);
        return this;
    }

    public ApiRequest setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
        return this;
    }

    public String getEndpoint() { return endpoint; }
    public String getMethod() { return method; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public Provider getProvider() { return provider; }
    public String getBody() { return body; }
    public Map<String, String> getHeaders() { return headers; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
}
