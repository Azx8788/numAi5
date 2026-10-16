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

    /** 去掉地址末尾斜杠 */
    public String normalizedBaseUrl() {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** 列表/按钮展示用名称 */
    public String displayName() {
        if (name != null && name.trim().length() > 0) return name.trim();
        return normalizedBaseUrl();
    }
}
