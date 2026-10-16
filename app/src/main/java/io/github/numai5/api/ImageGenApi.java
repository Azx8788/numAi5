package io.github.numai5.api;

import android.content.Context;
import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.github.numai5.provider.Provider;
import io.github.numai5.vision.ImageCompressor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 生图接口（OpenAI 兼容 /images/generations）。
 * 返回的图片可能是 b64_json 或 url，两种都支持；落盘为 filesDir/images/gen_*.png。
 * 生图耗时较长，读超时放宽到 300s。
 */
public class ImageGenApi {
    private static final String TAG = "ImageGenApi";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    /** 生图结果：已保存到本地的文件名列表 + 服务端改写后的提示词（可选） */
    public static class Outcome {
        public final List<String> filenames = new ArrayList<>();
        public String revisedPrompt;
    }

    /**
     * 文生图。失败抛 ApiError（含服务端 error.message）。
     * response_format 若被上游拒绝会自动去掉重试一次（gpt-image 系不接受该字段）。
     */
    public static Outcome generate(Context context, Provider provider, String modelId,
                                   String prompt, String size, int n) throws ApiError {
        if (provider == null || !provider.isValidForFetch()) {
            throw new ApiError("供应商未配置中转地址或 Key");
        }
        if (prompt == null || prompt.trim().length() == 0) {
            throw new ApiError("提示词不能为空");
        }

        try {
            return requestOnce(context, provider, modelId, prompt, size, n, true);
        } catch (ApiError e) {
            String raw = e.raw != null ? e.raw.toLowerCase() : "";
            if (raw.contains("response_format") || (e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("response_format"))) {
                // 该模型不接受 response_format，去掉后重试
                return requestOnce(context, provider, modelId, prompt, size, n, false);
            }
            throw e;
        }
    }

    private static Outcome requestOnce(Context context, Provider provider, String modelId,
                                       String prompt, String size, int n, boolean withResponseFormat)
            throws ApiError {
        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("prompt", prompt);
        body.addProperty("n", n > 1 ? n : 1);
        if (size != null && size.length() > 0 && !"auto".equals(size)) {
            body.addProperty("size", size);
        }
        if (withResponseFormat) {
            body.addProperty("response_format", "b64_json");
        }

        String url = provider.normalizedBaseUrl() + "/images/generations";
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + provider.apiKey)
                .header("Content-Type", "application/json");
        // 已知会对客户端做指纹校验的站点（如 agentrouter.org），自动补上兼容头
        ApiCompatHeaders.apply(requestBuilder, url);
        Request request = requestBuilder
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new ApiError(extractError(text, response.code()), false, text);
            }
            return parse(context, text);
        } catch (IOException e) {
            throw new ApiError("生图请求失败：" + e.getMessage(), true, null);
        }
    }

    private static Outcome parse(Context context, String text) throws ApiError {
        Outcome outcome = new Outcome();
        JsonObject obj;
        try {
            obj = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            throw new ApiError("生图响应解析失败", false, text);
        }
        if (obj.has("error") && !obj.get("error").isJsonNull()) {
            throw new ApiError(extractError(text, 0), false, text);
        }
        JsonArray data = obj.getAsJsonArray("data");
        if (data == null || data.size() == 0) {
            throw new ApiError("生图响应中没有图片", false, text);
        }
        for (int i = 0; i < data.size(); i++) {
            JsonElement element = data.get(i);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();

            if (item.has("revised_prompt") && !item.get("revised_prompt").isJsonNull()) {
                String revised = item.get("revised_prompt").getAsString();
                if (revised != null && revised.trim().length() > 0) outcome.revisedPrompt = revised.trim();
            }

            byte[] bytes = null;
            if (item.has("b64_json") && !item.get("b64_json").isJsonNull()) {
                try {
                    bytes = android.util.Base64.decode(item.get("b64_json").getAsString(), android.util.Base64.DEFAULT);
                } catch (Exception e) {
                    Log.e(TAG, "decode b64 image failed", e);
                }
            }
            if (bytes == null && item.has("url") && !item.get("url").isJsonNull()) {
                bytes = download(item.get("url").getAsString());
            }
            if (bytes != null) {
                String filename = ImageCompressor.saveGeneratedImage(context, bytes);
                if (filename != null) outcome.filenames.add(filename);
            }
        }
        if (outcome.filenames.isEmpty()) {
            throw new ApiError("生图响应中没有可用图片数据", false, text);
        }
        return outcome;
    }

    private static byte[] download(String url) {
        if (url == null || url.length() == 0) return null;
        try {
            Request.Builder builder = new Request.Builder().url(url);
            ApiCompatHeaders.apply(builder, url);
            Request request = builder.get().build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                ResponseBody body = response.body();
                return body != null ? body.bytes() : null;
            }
        } catch (Exception e) {
            Log.e(TAG, "download image failed", e);
            return null;
        }
    }

    /** 解析 {"error":{"message":"..."}} */
    private static String extractError(String body, int code) {
        if (body != null && body.length() > 0) {
            try {
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                if (obj.has("error")) {
                    JsonElement err = obj.get("error");
                    if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
                        return err.getAsJsonObject().get("message").getAsString();
                    }
                    if (err.isJsonPrimitive()) return err.getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        return code > 0 ? ("HTTP " + code) : "生图请求失败";
    }
}
