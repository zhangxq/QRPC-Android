package com.innotech.qrpc;

public interface TCallback<T> {

    // 成功的回调
    void onSuccess(T t);

    // 失败的回调
    void onFailure(String msg);
}
