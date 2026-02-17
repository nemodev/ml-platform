package com.mlplatform.service;

public class JupyterHubUnavailableException extends RuntimeException {
    public JupyterHubUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
