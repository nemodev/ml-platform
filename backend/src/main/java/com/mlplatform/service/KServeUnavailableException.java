package com.mlplatform.service;

public class KServeUnavailableException extends RuntimeException {

    public KServeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public KServeUnavailableException(String message) {
        super(message);
    }
}
