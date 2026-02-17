package com.mlplatform.service;

public class NotebookStorageUnavailableException extends RuntimeException {
    public NotebookStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
