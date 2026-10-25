package io.github.numai5.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商：一个独立的中转站（自己的 baseUrl / apiKey）+ 该站点的模型列表。
 * 不限个数，设置页可增删改；发送时按所选模型找到所属供应商取地址与 Key。
 */
public class Provider {
    public String id;
    public String name = "";
    public String baseUrl = "";
    public String apiKey = "";
    public List<ModelDef> models = new ArrayList<>();
    /**
     * 供应商类型，决定生图等非对话接口的调用方式：
     * - {@link #TYPE_OPENAI}（默认）：OpenAI 兼容，生图走同步 /images/generations
     * - {@link #TYPE_MODELSCOPE}：魔搭社区，生图必须走「提交任务 + 轮询」的异步流程
     */
    public String type = TYPE_OPENAI;

    public static final String TYPE_OPENAI = "openai";
    public static final String TYPE_MODELSCOPE = "modelscope";

    /**
     * 客户端限流：同一供应商两次请求开始之间至少间隔的秒数。
     * 0 表示只串行（同时只发一个请求），不额外等待。
     * 中转站按并发或频率限速时报 429 时调大这个值。
     */
    public int minIntervalSec = 0;

    public Provider() {
    }

    public Provider(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** 拉取 /models 需要地址与 Key 都填好 */
    public boolean isValidForFetch() {
        return baseUrl != null && baseUrl.length() > 0
                && apiKey != null && apiKey.length() > 0;
    }

    /** 是否为魔搭（异步生图、errors 复数错误结构等差异按此判断） */
    public boolean isModelScope() {
        return TYPE_MODELSCOPE.equalsIgnoreCase(type);
    }

    /** 去掉地址末尾斜杠 */
    public String normalizedBaseUrl() {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** 客户端限流使用的键：优先 id，其次地址 */
    public String rateKey() {
        if (id != null && id.length() > 0) return id;
        String url = normalizedBaseUrl();
        return url.length() > 0 ? url : "default";
    }

    /** 列表/按钮展示用名称 */
    public String displayName() {
        if (name != null && name.trim().length() > 0) return name.trim();
        return normalizedBaseUrl();
    }
}
