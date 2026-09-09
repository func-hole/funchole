package com.funchole.backend.gateway.flow;

public record RouteKey(String method, String path) {

    public RouteKey {
        method = method == null ? "" : method.trim().toUpperCase();
        path = normalizePath(path);
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null) {
            return "/";
        }
        String path = rawPath.trim();
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        if (path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
