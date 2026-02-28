package com.mlplatform.exception;

public class ImageBuildUnavailableException extends RuntimeException {

    public ImageBuildUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ImageBuildUnavailableException(String message) {
        super(message);
    }
}
