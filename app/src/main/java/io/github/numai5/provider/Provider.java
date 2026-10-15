package io.github.numai5.provider;

/** 供应商：中转模式下为逻辑分组（UI 展示 + 默认模型归属），baseUrl/key 全局一套。 */
public class Provider {
    public String id;
    public String name;

    public Provider() {
    }

    public Provider(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
