package io.github.numai5.api;

import android.content.Context;
import android.util.JsonReader;
import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import io.github.numai5.R;
import io.github.numai5.provider.Provider;
import io.github.numai5.vision.ImageCompressor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
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
        return generate(context, provider, modelId, prompt, size, n, null);
    }

    /**
     * @param refImages 本地参考图文件名（ImageCompressor 保存的 filesDir/images 文件名）；
     *                  最多取前 3 张。魔搭走异步任务，OpenAI 兼容走 /images/edits multipart。
     */
    public static Outcome generate(Context context, Provider provider, String modelId,
                                   String prompt, String size, int n,
                                   List<String> refImages) throws ApiError {
        if (provider == null || !provider.isValidForFetch()) {
            throw new ApiError("供应商未配置中转地址或 Key");
        }
        if (prompt == null || prompt.trim().length() == 0) {
            throw new ApiError("提示词不能为空");
        }

        // 魔搭是异步任务制：提交拿 task_id，再轮询任务状态取图
        if (provider.isModelScope()) {
            return generateModelScope(context, provider, modelId, prompt, size, refImages);
        }

        // 有参考图走编辑接口（images/edits multipart），无图走生成接口（images/generations JSON）
        if (refImages != null && !refImages.isEmpty()) {
            return requestEdit(context, provider, modelId, prompt, size, n, refImages);
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

    // ===== 魔搭（ModelScope）：异步任务制生图 =====

    /** 轮询间隔与上限：魔搭出图通常 5~30 秒，给足 150 秒 */
    private static final long MODELSCOPE_POLL_INTERVAL_MS = 3000L;
    private static final long MODELSCOPE_POLL_TIMEOUT_MS = 150000L;

    /**
     * 魔搭生图：提交任务 + 轮询结果。
     *
     * 注意：提交响应里可能已经带 task_status=SUCCEED，但那不代表图片就绪，
     * 必须再查询 /v1/tasks/{task_id} 才能拿到 output_images。
     *
     * @param modelId 必须是带 org 的完整模型 id（如 Qwen/Qwen-Image）
     */
    private static Outcome generateModelScope(Context context, Provider provider, String modelId,
                                              String prompt, String size, List<String> refImages)
            throws ApiError {
        String base = provider.normalizedBaseUrl();
        List<JsonObject> attempts = buildModelScopeBodies(context, modelId, prompt, size, refImages);
        String submitUrl = base + "/images/generations";

        String taskId = null;
        ApiError lastFieldError = null;
        for (JsonObject body : attempts) {
            try {
                taskId = submitModelScope(provider, submitUrl, body);
                break;
            } catch (ApiError e) {
                // 只有字段类错误才继续换字段名；鉴权/额度/网络错误直接抛出。
                if (attempts.size() > 1 && isFieldError(e)) {
                    lastFieldError = e;
                    continue;
                }
                throw e;
            }
        }
        if (taskId == null || taskId.length() == 0) {
            throw lastFieldError != null ? lastFieldError
                    : new ApiError("魔搭未返回 task_id，请稍后重试");
        }

        String taskUrl = base + "/tasks/" + taskId;
        long deadline = System.currentTimeMillis() + MODELSCOPE_POLL_TIMEOUT_MS;
        String lastStatus = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(MODELSCOPE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiError("生图任务被中断", false, null);
            }

            Request.Builder pollBuilder = new Request.Builder()
                    .url(taskUrl)
                    .header("Authorization", "Bearer " + ApiAuth.sanitize(provider.apiKey))
                    .header("X-ModelScope-Task-Type", "image_generation");
            ApiCompatHeaders.apply(pollBuilder, taskUrl);
            String text;
            try (Response response = CLIENT.newCall(pollBuilder.get().build()).execute()) {
                ResponseBody responseBody = response.body();
                text = responseBody != null ? responseBody.string() : "";
                if (!response.isSuccessful()) {
                    // 轮询偶发 5xx/限流不致命，继续等下一轮
                    continue;
                }
            } catch (IOException e) {
                continue;
            }

            JsonObject obj;
            try {
                obj = JsonParser.parseString(text).getAsJsonObject();
            } catch (Exception e) {
                continue;
            }
            String status = obj.has("task_status") && !obj.get("task_status").isJsonNull()
                    ? obj.get("task_status").getAsString() : null;
            lastStatus = status;
            if ("SUCCEED".equals(status)) {
                JsonArray images = obj.getAsJsonArray("output_images");
                if (images == null || images.size() == 0) {
                    throw new ApiError("魔搭任务成功但没有返回图片", false, text);
                }
                Outcome outcome = new Outcome();
                for (int i = 0; i < images.size(); i++) {
                    String url = images.get(i).isJsonNull() ? null : images.get(i).getAsString();
                    byte[] bytes = download(url);
                    if (bytes != null) {
                        String filename = ImageCompressor.saveGeneratedImage(context, bytes);
                        if (filename != null) outcome.filenames.add(filename);
                    }
                }
                if (outcome.filenames.isEmpty()) {
                    throw new ApiError("魔搭返回的图片下载失败，请检查网络后重试", false, text);
                }
                return outcome;
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                throw new ApiError(extractError(text, 0), false, text);
            }
            // PENDING / RUNNING 等继续轮询
        }
        throw new ApiError("魔搭生图超时（已等待 "
                + (MODELSCOPE_POLL_TIMEOUT_MS / 1000) + " 秒"
                + (lastStatus != null ? "，最后状态 " + lastStatus : "")
                + "），请稍后重试", true, null);
    }

    /**
     * 图生图字段在魔搭文档与示例之间不完全一致：
     * 官方参数表写 images（base64 数组），模型页示例写 image_url（URL 数组）。
     * 本地只有文件，没有公网 URL，因此按「images 原始 base64 -> image_url data URI ->
     * images data URI」三档尝试，只有字段类错误才继续下一档。
     */
    private static List<JsonObject> buildModelScopeBodies(Context context, String modelId,
                                                          String prompt, String size,
                                                          List<String> refImages) throws ApiError {
        List<JsonObject> bodies = new ArrayList<>();
        if (refImages == null || refImages.isEmpty()) {
            JsonObject body = baseModelScopeBody(modelId, prompt);
            String normalized = normalizeModelScopeSize(modelId, size);
            if (normalized != null) body.addProperty("size", normalized);
            bodies.add(body);
            return bodies;
        }

        List<String> encoded = new ArrayList<>();
        for (String filename : refImages) {
            if (encoded.size() >= 3) break;
            File file = ImageCompressor.imageFile(context, filename);
            String b64 = ImageCompressor.toBase64(file);
            if (b64 != null && b64.length() > 0) encoded.add(b64);
        }
        if (encoded.isEmpty()) {
            throw new ApiError("参考图读取失败，请重新选择", false, null);
        }

        JsonArray raw = new JsonArray();
        JsonArray dataUri = new JsonArray();
        for (String b64 : encoded) {
            raw.add(b64);
            dataUri.add("data:image/jpeg;base64," + b64);
        }

        JsonObject imagesRaw = baseModelScopeBody(modelId, prompt);
        imagesRaw.add("images", raw);
        bodies.add(imagesRaw);

        JsonObject imageUrl = baseModelScopeBody(modelId, prompt);
        imageUrl.add("image_url", dataUri);
        bodies.add(imageUrl);

        JsonObject imagesDataUri = baseModelScopeBody(modelId, prompt);
        imagesDataUri.add("images", dataUri);
        bodies.add(imagesDataUri);
        return bodies;
    }

    private static JsonObject baseModelScopeBody(String modelId, String prompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("prompt", prompt);
        return body;
    }

    private static String submitModelScope(Provider provider, String submitUrl, JsonObject body)
            throws ApiError {
        Request.Builder submitBuilder = new Request.Builder()
                .url(submitUrl)
                .header("Authorization", "Bearer " + ApiAuth.sanitize(provider.apiKey))
                .header("Content-Type", "application/json")
                .header("X-ModelScope-Async-Mode", "true");
        ApiCompatHeaders.apply(submitBuilder, submitUrl);

        try (Response response = CLIENT.newCall(submitBuilder
                .post(RequestBody.create(body.toString(), JSON)).build()).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new ApiError(extractError(text, response.code()), false, text,
                        response.code(), null, false, 0);
            }
            JsonObject obj;
            try {
                obj = JsonParser.parseString(text).getAsJsonObject();
            } catch (Exception e) {
                throw new ApiError("魔搭提交任务响应解析失败", false, text);
            }
            if (obj.has("errors") || obj.has("error")) {
                throw new ApiError(extractError(text, 0), false, text);
            }
            String taskId = obj.has("task_id") && !obj.get("task_id").isJsonNull()
                    ? obj.get("task_id").getAsString() : null;
            if (taskId == null || taskId.length() == 0) {
                throw new ApiError("魔搭未返回 task_id，请稍后重试", false, text);
            }
            return taskId;
        } catch (IOException e) {
            throw new ApiError("魔搭提交任务失败：" + e.getMessage(), true, null);
        }
    }

    private static boolean isFieldError(ApiError error) {
        if (error == null) return false;
        if (error.httpCode != 0 && error.httpCode != 400 && error.httpCode != 422) return false;
        String message = (error.getMessage() == null ? "" : error.getMessage()) + " "
                + (error.raw == null ? "" : error.raw);
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("image_url")
                || lower.contains("images")
                || lower.contains("field required")
                || lower.contains("missing field")
                || lower.contains("invalid field")
                || lower.contains("unknown field");
    }

    /**
     * 魔搭各模型的尺寸范围不同（实测：Qwen-Image 64~1664、Z-Image 512~2048、
     * FLUX 64~1024，超出直接 400 width must be integer in [64,2048]），
     * 且要求边长是 8 的倍数。这里按模型把用户选的尺寸等比缩进合法区间。
     */
    private static String normalizeModelScopeSize(String modelId, String size) {
        if (size == null || size.length() == 0 || "auto".equalsIgnoreCase(size)) return null;
        int sep = size.indexOf('x');
        if (sep <= 0) return null;
        int width;
        int height;
        try {
            width = Integer.parseInt(size.substring(0, sep).trim());
            height = Integer.parseInt(size.substring(sep + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (width <= 0 || height <= 0) return null;

        String model = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        int low;
        int high;
        if (model.contains("z-image")) {
            low = 512;
            high = 2048;
        } else if (model.contains("flux")) {
            low = 64;
            high = 1024;
        } else if (model.contains("qwen-image")) {
            low = 64;
            high = 1664;
        } else if (model.contains("sd") || model.contains("stable-diffusion")) {
            low = 64;
            high = 2048;
        } else {
            low = 512;
            high = 1024;
        }

        // 先按比例缩到上限内（保持长宽比），再保证不小于下限
        int maxSide = Math.max(width, height);
        if (maxSide > high) {
            double ratio = (double) high / maxSide;
            width = (int) Math.round(width * ratio);
            height = (int) Math.round(height * ratio);
        }
        width = clampToStep(width, low, high);
        height = clampToStep(height, low, high);
        return width + "x" + height;
    }

    /** 取 8 的倍数并夹在 [low, high] 内 */
    private static int clampToStep(int value, int low, int high) {
        int v = Math.round(value / 8f) * 8;
        if (v < low) v = ((low + 7) / 8) * 8;
        if (v > high) v = (high / 8) * 8;
        return v;
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
                .header("Authorization", "Bearer " + ApiAuth.sanitize(provider.apiKey))
                .header("Content-Type", "application/json");
        // 已知会对客户端做指纹校验的站点（如 agentrouter.org），自动补上兼容头
        ApiCompatHeaders.apply(requestBuilder, url);
        Request request = requestBuilder
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                ResponseBody responseBody = response.body();
                String text = responseBody != null ? responseBody.string() : "";
                throw new ApiError(extractError(text, response.code()), false, text);
            }
            return parseResponse(context, response);
        } catch (java.net.SocketTimeoutException e) {
            throw new ApiError(context.getString(R.string.imagegen_timeout_billing), true, null);
        } catch (IOException e) {
            throw new ApiError("生图请求失败：" + e.getMessage(), true, null);
        }
    }

    /**
     * 图生图：multipart/form-data 上传到 /images/edits。
     * 单图字段名 image，多图 image[]；图片为 JPEG（ImageCompressor 固定输出 .jpg）。
     * 不加 response_format：GPT 图像模型总是返回 base64，dall-e-2 才支持该参数。
     * 参考文件不可读时直接报错，不跳过部分图片。
     */
    private static Outcome requestEdit(Context context, Provider provider, String modelId,
                                       String prompt, String size, int n,
                                       List<String> refImages) throws ApiError {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", modelId)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("n", String.valueOf(n > 1 ? n : 1));
        if (size != null && size.length() > 0 && !"auto".equals(size)) {
            builder.addFormDataPart("size", size);
        }

        String imageField = refImages.size() == 1 ? "image" : "image[]";
        MediaType jpeg = MediaType.parse("image/jpeg");
        for (String filename : refImages) {
            File file = ImageCompressor.imageFile(context, filename);
            if (file == null || !file.exists() || file.length() == 0) {
                throw new ApiError("参考图读取失败，请重新选择", false, null);
            }
            builder.addFormDataPart(imageField, filename,
                    RequestBody.create(file, jpeg));
        }

        String url = provider.normalizedBaseUrl() + "/images/edits";
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + ApiAuth.sanitize(provider.apiKey));
        ApiCompatHeaders.apply(requestBuilder, url);
        Request request = requestBuilder.post(builder.build()).build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                ResponseBody responseBody = response.body();
                String text = responseBody != null ? responseBody.string() : "";
                String error = extractError(text, response.code());
                if (response.code() == 400 || response.code() == 404 || response.code() == 405) {
                    error += "\n" + context.getString(R.string.imagegen_ref_only_modelscope);
                }
                throw new ApiError(error, false, text, response.code(), null, false, 0);
            }
            return parseResponse(context, response);
        } catch (java.net.SocketTimeoutException e) {
            throw new ApiError(context.getString(R.string.imagegen_timeout_billing), true, null);
        } catch (IOException e) {
            throw new ApiError("图生图请求失败：" + e.getMessage(), true, null);
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
        if ((obj.has("error") && !obj.get("error").isJsonNull())
                || (obj.has("errors") && !obj.get("errors").isJsonNull())) {
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

    /**
     * 将成功响应体流式写入缓存文件，再用 JsonReader 解析，避免 body.string() 把整个 JSON 载入堆。
     * b64_json 值仍会创建完整 String（JsonReader.nextString 的固有限制），但解析后立即解码落盘并释放引用，
     * 不再同时持有「完整 JSON 字符串 + JsonObject + b64 字符串」三份副本。
     */
    private static Outcome parseResponse(Context context, Response response) throws ApiError {
        ResponseBody body = response.body();
        if (body == null) {
            throw new ApiError("生图响应为空", false, null);
        }
        File tempFile = new File(context.getCacheDir(),
                "img_resp_" + System.currentTimeMillis() + ".json");
        try {
            try (InputStream is = body.byteStream();
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
            return parseFromFile(context, tempFile);
        } catch (OutOfMemoryError e) {
            throw new ApiError(context.getString(R.string.imagegen_oom), false, null);
        } catch (IOException e) {
            throw new ApiError("生图响应读取失败", false, null);
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
    }

    private static Outcome parseFromFile(Context context, File file) throws ApiError, IOException {
        Outcome outcome = new Outcome();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             JsonReader reader = new JsonReader(
                     new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("data".equals(name)) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.beginObject();
                        String b64 = null;
                        String url = null;
                        String revised = null;
                        while (reader.hasNext()) {
                            String key = reader.nextName();
                            if ("b64_json".equals(key)) {
                                b64 = reader.nextString();
                            } else if ("url".equals(key)) {
                                url = reader.nextString();
                            } else if ("revised_prompt".equals(key)) {
                                revised = reader.nextString();
                            } else {
                                reader.skipValue();
                            }
                        }
                        reader.endObject();
                        if (revised != null && revised.trim().length() > 0) {
                            outcome.revisedPrompt = revised.trim();
                        }
                        byte[] bytes = null;
                        if (b64 != null && b64.length() > 0) {
                            try {
                                bytes = android.util.Base64.decode(b64,
                                        android.util.Base64.DEFAULT);
                            } catch (Exception e) {
                                Log.e(TAG, "decode b64 image failed", e);
                            }
                        }
                        if (bytes == null && url != null && url.length() > 0) {
                            bytes = download(url);
                        }
                        if (bytes != null) {
                            String filename = ImageCompressor.saveGeneratedImage(context, bytes);
                            if (filename != null) outcome.filenames.add(filename);
                        }
                        b64 = null;
                        bytes = null;
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } catch (OutOfMemoryError e) {
            throw new ApiError(context.getString(R.string.imagegen_oom), false, null);
        }
        if (outcome.filenames.isEmpty()) {
            throw new ApiError("生图响应中没有可用图片数据", false, null);
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
                // 魔搭用 errors（复数），OpenAI 系用 error（单数），两者都要认
                for (String key : new String[]{"error", "errors"}) {
                    if (!obj.has(key) || obj.get(key).isJsonNull()) continue;
                    JsonElement err = obj.get(key);
                    if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
                        return err.getAsJsonObject().get("message").getAsString();
                    }
                    if (err.isJsonPrimitive()) return err.getAsString();
                }
                if (obj.has("message") && !obj.get("message").isJsonNull()) {
                    return obj.get("message").getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        return code > 0 ? ("HTTP " + code) : "生图请求失败";
    }
}
