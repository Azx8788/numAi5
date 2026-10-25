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
import io.github.numai5.data.ProviderRateLimiter;
import io.github.numai5.provider.Provider;
import io.github.numai5.vision.ImageCompressor;
import okhttp3.Call;
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
 *
 * 限流（v1.4）：每次实际 HTTP 请求（提交、字段回退、每次轮询）都单独经
 * ProviderRateLimiter 申请一次供应商额度，等待期间不占并发许可；
 * 429 不再被静默吞掉并继续轮询，而是按服务端 Retry-After 结束本次操作并冷却供应商。
 * CDN 图片下载不占供应商额度。
 */
public class ImageGenApi {
    private static final String TAG = "ImageGenApi";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 单次生图操作的 HTTP 请求预算（提交 + 字段回退 + 轮询） */
    private static final int IMAGE_REQUEST_BUDGET = 40;

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

    /** 单次生图操作的请求预算：提交、字段回退、每次轮询都从这里扣一次 */
    private static final class Budget {
        private int remaining = IMAGE_REQUEST_BUDGET;

        synchronized boolean take() {
            if (remaining <= 0) return false;
            remaining--;
            return true;
        }

        synchronized int remaining() {
            return remaining;
        }
    }

    private interface GuardedCall<T> {
        T run(ProviderRateLimiter.Entry entry) throws ApiError, IOException;
    }

    /**
     * 在供应商调度器内执行一次 HTTP 请求：排队 → 获准 → 执行 → 释放（只释放一次）。
     * 失败时把错误分类交给 RateLimitPolicy（429 冷却、额度/认证暂停）。
     */
    private static <T> T guarded(Provider provider, Budget budget,
                                 GuardedCall<T> body) throws ApiError {
        if (budget != null && !budget.take()) {
            throw new ApiError("本次生图已达到请求次数上限（" + IMAGE_REQUEST_BUDGET
                    + " 次），请稍后重试", true, null);
        }
        ProviderRateLimiter.Entry entry = ProviderRateLimiter.submit(
                provider, ProviderRateLimiter.RequestType.IMAGE,
                ProviderRateLimiter.Defaults.IMAGE_WAIT_MS);
        ProviderRateLimiter.Denial denial = ProviderRateLimiter.awaitTurn(entry);
        if (denial != null) {
            throw RateLimitPolicy.denialError(denial);
        }
        if (!ProviderRateLimiter.markRunning(entry)) {
            ProviderRateLimiter.finish(entry, ProviderRateLimiter.State.CANCELLED, null);
            throw new ApiError("生图请求已取消", false, null);
        }
        ProviderRateLimiter.State terminal = ProviderRateLimiter.State.FAILED;
        try {
            T value = body.run(entry);
            terminal = ProviderRateLimiter.State.COMPLETED;
            return value;
        } catch (ApiError e) {
            RateLimitPolicy.report(provider, e);
            throw e;
        } catch (IOException e) {
            // 被取消时 OkHttp 抛 "Canceled"：转成明确的取消提示，而不是当成网络错误
            if (entry.isCancelRequested()) {
                throw new ApiError("生图请求已取消", false, null);
            }
            boolean timeout = e instanceof java.net.SocketTimeoutException;
            throw new ApiError("生图网络错误：" + e.getMessage(), timeout, null);
        } finally {
            ProviderRateLimiter.finish(entry, terminal, null);
        }
    }

    /** 绑定调度条目并发起调用，使取消能真正断开连接 */
    private static Response execute(ProviderRateLimiter.Entry entry, Request request) throws IOException {
        Call call = CLIENT.newCall(request);
        ProviderRateLimiter.bind(entry, call::cancel);
        return call.execute();
    }

    /** 在原始错误提示后追加一句排障建议，保留原有分类信息 */
    private static ApiError withHint(ApiError base, String hint) {
        if (base == null) return new ApiError(hint);
        String message = base.getMessage() == null ? hint : base.getMessage() + "\n" + hint;
        return new ApiError(message, base.isTimeout, base.raw, base.httpCode, base.errorCode,
                base.retryable, base.retryAfterSeconds, base.kind);
    }

    /** 上游 HTTP 失败 → 带分类的 ApiError（Retry-After 交给调度器做冷却） */
    private static ApiError httpError(String text, int code, String retryAfterHeader) {
        ApiError mapped = ErrorMapper.fromHttp(code, text, retryAfterHeader);
        return mapped;
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
            return generateModelScope(context, provider, modelId, prompt, size, refImages, new Budget());
        }

        Budget budget = new Budget();
        // 有参考图：商汤日日新用 JSON 的 images 数组，其余 OpenAI 兼容站用 multipart
        if (refImages != null && !refImages.isEmpty()) {
            if (provider.isSenseNova()) {
                return requestEditJson(context, provider, modelId, prompt, size, refImages, budget);
            }
            return requestEdit(context, provider, modelId, prompt, size, n, refImages, budget);
        }

        try {
            return requestOnce(context, provider, modelId, prompt, size, n, true, budget);
        } catch (ApiError e) {
            // 只有明确是这个字段不被接受、且预算还够时，才做一次受控修正请求
            String raw = e.raw != null ? e.raw.toLowerCase() : "";
            String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            boolean fieldRejected = raw.contains("response_format") || message.contains("response_format");
            if (!fieldRejected || e.kind == ErrorMapper.Kind.RATE_LIMIT
                    || e.kind == ErrorMapper.Kind.QUOTA || e.kind == ErrorMapper.Kind.AUTH) {
                throw e;
            }
            return requestOnce(context, provider, modelId, prompt, size, n, false, budget);
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
                                              String prompt, String size, List<String> refImages,
                                              Budget budget) throws ApiError {
        String base = provider.normalizedBaseUrl();
        List<JsonObject> attempts = buildModelScopeBodies(context, modelId, prompt, size, refImages);
        String submitUrl = base + "/images/generations";

        String taskId = null;
        ApiError lastFieldError = null;
        for (JsonObject body : attempts) {
            try {
                taskId = submitModelScope(provider, submitUrl, body, budget);
                break;
            } catch (ApiError e) {
                // 只有字段类错误才继续换字段名；鉴权/额度/网络/限流错误直接抛出。
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
        // 单调时钟计时，避免用户改系统时间影响超时判定
        long deadlineNs = System.nanoTime() + MODELSCOPE_POLL_TIMEOUT_MS * 1000000L;
        // 取消计数：用户取消后不再发起新的轮询请求
        final long cancelEpoch = ProviderRateLimiter.cancelEpoch(
                provider, ProviderRateLimiter.RequestType.IMAGE);
        String lastStatus = null;
        ApiError lastTransportError = null;
        while (System.nanoTime() < deadlineNs) {
            if (ProviderRateLimiter.cancelEpoch(provider, ProviderRateLimiter.RequestType.IMAGE)
                    != cancelEpoch) {
                throw new ApiError("生图请求已取消", false, null);
            }
            try {
                Thread.sleep(MODELSCOPE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiError("生图任务被中断", false, null);
            }
            if (ProviderRateLimiter.cancelEpoch(provider, ProviderRateLimiter.RequestType.IMAGE)
                    != cancelEpoch) {
                throw new ApiError("生图请求已取消", false, null);
            }

            Request.Builder pollBuilder = new Request.Builder()
                    .url(taskUrl)
                    .header("Authorization", "Bearer " + ApiAuth.sanitize(provider.apiKey))
                    .header("X-ModelScope-Task-Type", "image_generation");
            ApiCompatHeaders.apply(pollBuilder, taskUrl);
            final Request pollRequest = pollBuilder.get().build();

            // 每次轮询单独申请一次供应商额度；两次轮询之间不占用 HTTP 并发许可
            String text;
            try {
                text = guarded(provider, budget, entry -> {
                    try (Response response = execute(entry, pollRequest)) {
                        ResponseBody responseBody = response.body();
                        String bodyText = responseBody != null ? responseBody.string() : "";
                        if (!response.isSuccessful()) {
                            // 429 / 额度 / 认证必须让调用方看到，不能静默继续轮询
                            throw httpError(bodyText, response.code(), response.header("Retry-After"));
                        }
                        return bodyText;
                    }
                });
            } catch (ApiError e) {
                if (e.kind == ErrorMapper.Kind.RATE_LIMIT || e.kind == ErrorMapper.Kind.QUOTA
                        || e.kind == ErrorMapper.Kind.AUTH) {
                    throw withHint(e, "任务可能仍在服务端生成，请稍后到平台查看结果");
                }
                // 网络/5xx：在预算与总时限内继续轮询（排队等待不算进总时限）
                lastTransportError = e;
                deadlineNs += ProviderRateLimiter.lastWaitMs() * 1000000L;
                continue;
            }

            // 排队等许可的时间不算进生图总时限：后台绘画被聊天占住时不会因此被判超时
            deadlineNs += ProviderRateLimiter.lastWaitMs() * 1000000L;

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
                    // CDN 下载不占供应商额度
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
        String suffix = lastStatus != null ? "，最后状态 " + lastStatus : "";
        if (lastTransportError != null) {
            suffix += "；最后一次错误：" + lastTransportError.getMessage();
        }
        throw new ApiError("魔搭生图超时（已等待 "
                + (MODELSCOPE_POLL_TIMEOUT_MS / 1000) + " 秒" + suffix
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

    private static String submitModelScope(Provider provider, String submitUrl, JsonObject body,
                                           Budget budget) throws ApiError {
        Request.Builder submitBuilder = new Request.Builder()
                .url(submitUrl)
                .header("Authorization", "Bearer " + ApiAuth.sanitize(provider.apiKey))
                .header("Content-Type", "application/json")
                .header("X-ModelScope-Async-Mode", "true");
        ApiCompatHeaders.apply(submitBuilder, submitUrl);
        final Request submitRequest = submitBuilder
                .post(RequestBody.create(body.toString(), JSON)).build();

        String text = guarded(provider, budget, entry -> {
            try (Response response = execute(entry, submitRequest)) {
                ResponseBody responseBody = response.body();
                String bodyText = responseBody != null ? responseBody.string() : "";
                if (!response.isSuccessful()) {
                    throw httpError(bodyText, response.code(), response.header("Retry-After"));
                }
                return bodyText;
            }
        });

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
                                       String prompt, String size, int n, boolean withResponseFormat,
                                       Budget budget)
            throws ApiError {
        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("prompt", prompt);
        // 商汤 U1.5 仅支持 n=1
        body.addProperty("n", provider.isSenseNova() ? 1 : (n > 1 ? n : 1));
        String normalizedSize = provider.isSenseNova() ? normalizeSenseNovaSize(size) : size;
        if (normalizedSize != null && normalizedSize.length() > 0 && !"auto".equals(normalizedSize)) {
            body.addProperty("size", normalizedSize);
        }
        if (withResponseFormat) {
            body.addProperty("response_format", "b64_json");
        }
        if (provider.isSenseNova()) {
            // 官方参数：关闭 Logo 水印（公测期免费）、开启提示词润色、固定 PNG 输出
            body.addProperty("watermark", false);
            body.addProperty("prompt_extend", true);
            body.addProperty("output_format", "png");
        }

        String url = provider.normalizedBaseUrl() + "/images/generations";
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + ApiAuth.sanitize(provider.apiKey))
                .header("Content-Type", "application/json");
        // 已知会对客户端做指纹校验的站点（如 agentrouter.org），自动补上兼容头
        ApiCompatHeaders.apply(requestBuilder, url);
        final Request request = requestBuilder
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try {
            return guarded(provider, budget, entry -> {
                try (Response response = execute(entry, request)) {
                    if (!response.isSuccessful()) {
                        ResponseBody responseBody = response.body();
                        String text = responseBody != null ? responseBody.string() : "";
                        throw httpError(text, response.code(), response.header("Retry-After"));
                    }
                    return parseResponse(context, response);
                }
            });
        } catch (ApiError e) {
            if (e.isTimeout && e.httpCode == 0) {
                throw new ApiError(context.getString(R.string.imagegen_timeout_billing), true, null);
            }
            throw e;
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
                                       List<String> refImages, Budget budget) throws ApiError {
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
        final Request request = requestBuilder.post(builder.build()).build();

        try {
            return guarded(provider, budget, entry -> {
                try (Response response = execute(entry, request)) {
                    if (!response.isSuccessful()) {
                        ResponseBody responseBody = response.body();
                        String text = responseBody != null ? responseBody.string() : "";
                        ApiError error = httpError(text, response.code(), response.header("Retry-After"));
                        if (response.code() == 400 || response.code() == 404 || response.code() == 405) {
                            error = withHint(error, context.getString(R.string.imagegen_ref_only_modelscope));
                        }
                        throw error;
                    }
                    return parseResponse(context, response);
                }
            });
        } catch (ApiError e) {
            if (e.isTimeout && e.httpCode == 0) {
                throw new ApiError(context.getString(R.string.imagegen_timeout_billing), true, null);
            }
            throw e;
        }
    }

    /**
     * 商汤日日新的图生图：POST /images/edits，**JSON** 而不是 multipart。
     *
     * 请求体形如：
     * {"model":"sensenova-u1.5-lite","images":[{"image_url":"data:image/jpeg;base64,..."}],
     *  "prompt":"...","n":1,"size":"auto","response_format":"b64_json",
     *  "output_format":"png","watermark":false,"prompt_extend":true}
     *
     * 文档限制：至多 5 张参考图，第 1 张为主编辑图；n 只能为 1。
     */
    private static Outcome requestEditJson(Context context, Provider provider, String modelId,
                                           String prompt, String size,
                                           List<String> refImages, Budget budget) throws ApiError {
        JsonArray images = new JsonArray();
        for (String filename : refImages) {
            if (images.size() >= 5) break; // 官方上限 5 张
            File file = ImageCompressor.imageFile(context, filename);
            if (file == null || !file.exists() || file.length() == 0) {
                throw new ApiError("参考图读取失败，请重新选择", false, null);
            }
            String base64 = ImageCompressor.toBase64(file);
            if (base64 == null || base64.length() == 0) {
                throw new ApiError("参考图编码失败，请重新选择", false, null);
            }
            JsonObject item = new JsonObject();
            item.addProperty("image_url", "data:image/jpeg;base64," + base64);
            images.add(item);
        }
        if (images.size() == 0) {
            throw new ApiError("参考图读取失败，请重新选择", false, null);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.add("images", images);
        body.addProperty("prompt", prompt);
        body.addProperty("n", 1);
        String normalizedSize = normalizeSenseNovaSize(size);
        if (normalizedSize != null && !"auto".equals(normalizedSize)) {
            body.addProperty("size", normalizedSize);
        }
        body.addProperty("response_format", "b64_json");
        body.addProperty("output_format", "png");
        body.addProperty("watermark", false);
        body.addProperty("prompt_extend", true);

        String url = provider.normalizedBaseUrl() + "/images/edits";
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + ApiAuth.sanitize(provider.apiKey))
                .header("Content-Type", "application/json");
        ApiCompatHeaders.apply(requestBuilder, url);
        final Request request = requestBuilder
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try {
            return guarded(provider, budget, entry -> {
                try (Response response = execute(entry, request)) {
                    if (!response.isSuccessful()) {
                        ResponseBody responseBody = response.body();
                        String text = responseBody != null ? responseBody.string() : "";
                        throw httpError(text, response.code(), response.header("Retry-After"));
                    }
                    return parseResponse(context, response);
                }
            });
        } catch (ApiError e) {
            if (e.isTimeout && e.httpCode == 0) {
                throw new ApiError(context.getString(R.string.imagegen_timeout_billing), true, null);
            }
            throw e;
        }
    }

    /**
     * 商汤 U1.5 的尺寸约束：512~4096、必须为 32 的倍数、长宽比不超过 3:1。
     * 用户选 256x256 这类过小尺寸时等比放大到 512；不合法则回退 auto（由服务端按主图适配）。
     */
    private static String normalizeSenseNovaSize(String size) {
        if (size == null || size.length() == 0 || "auto".equalsIgnoreCase(size)) return "auto";
        int sep = size.indexOf('x');
        if (sep <= 0) return "auto";
        int width;
        int height;
        try {
            width = Integer.parseInt(size.substring(0, sep).trim());
            height = Integer.parseInt(size.substring(sep + 1).trim());
        } catch (NumberFormatException e) {
            return "auto";
        }
        if (width <= 0 || height <= 0) return "auto";
        // 长宽比超过 3:1 直接交给服务端 auto，避免裁剪
        if (Math.max(width, height) > Math.min(width, height) * 3) return "auto";
        width = clampSenseNovaSide(width);
        height = clampSenseNovaSide(height);
        return width + "x" + height;
    }

    private static int clampSenseNovaSide(int value) {
        int v = Math.round(value / 32f) * 32;
        if (v < 512) v = 512;
        if (v > 4096) v = 4096;
        return v;
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
