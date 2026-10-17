package io.github.numai5.api.protocol;

import io.github.numai5.api.ApiClient;
import io.github.numai5.provider.Provider;

/**
 * 协议分派：按供应商类型返回对应实现。
 * 默认 OpenAI 兼容；魔搭（ModelScope）走专属实现（POST /models、errors 结构等）。
 */
public final class Protocols {

    public static ProviderProtocol of(Provider provider, ApiClient apiClient) {
        if (provider != null && provider.isModelScope()) {
            return new ModelScopeProtocol(apiClient);
        }
        return new OpenAiProtocol(apiClient);
    }

    private Protocols() {
    }
}
