package io.github.numai5.api;

public interface ApiCallback<T> {
    void onSuccess(T result);
    void onError(ApiError error);
}
