package com.funchole.backend.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RouteKeyTest {

    @Test
    void stripsTrailingSlash() {
        assertEquals(new RouteKey("GET", "/orders"), new RouteKey("GET", "/orders/"));
        assertEquals(new RouteKey("GET", "/orders///"), new RouteKey("GET", "/orders"));
    }

    @Test
    void keepsRootPath() {
        assertEquals(new RouteKey("GET", "/"), new RouteKey("GET", "/"));
        assertEquals(new RouteKey("GET", ""), new RouteKey("GET", "/"));
    }

    @Test
    void ignoresQueryString() {
        assertEquals(new RouteKey("GET", "/orders"), new RouteKey("GET", "/orders?page=2&limit=10"));
    }

    @Test
    void uppercasesMethod() {
        assertEquals(new RouteKey("GET", "/orders"), new RouteKey("get", "/orders"));
    }

    @Test
    void distinguishesMethodAndPath() {
        assertEquals(false, new RouteKey("GET", "/orders").equals(new RouteKey("POST", "/orders")));
        assertEquals(false, new RouteKey("GET", "/orders").equals(new RouteKey("GET", "/checkout")));
    }
}
