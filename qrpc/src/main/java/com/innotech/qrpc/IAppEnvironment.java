package com.innotech.qrpc;

public interface IAppEnvironment {

    enum Environment {
        TEST,
        RELEASE
    }

    Environment getEnvironment();
}
