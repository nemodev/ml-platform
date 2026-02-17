package com.mlplatform.service;

public class MlflowUnavailableException extends RuntimeException {
    public MlflowUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
