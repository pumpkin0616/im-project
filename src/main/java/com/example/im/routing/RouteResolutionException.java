package com.example.im.routing;

public final class RouteResolutionException extends RuntimeException {
    private final String code;

    public RouteResolutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
