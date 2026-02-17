package com.mlplatform.service;

public class AirflowUnavailableException extends RuntimeException {
    public AirflowUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AirflowUnavailableException(String message) {
        super(message);
    }
}
