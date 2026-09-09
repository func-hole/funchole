package com.funchole.backend.invocation;

public final class DependencyGraphResolutionException extends RuntimeException {

    public DependencyGraphResolutionException(String message) {
        super(message);
    }

    public DependencyGraphResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
