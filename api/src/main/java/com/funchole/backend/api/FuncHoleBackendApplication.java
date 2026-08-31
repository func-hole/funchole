package com.funchole.backend.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.funchole.backend")
public class FuncHoleBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FuncHoleBackendApplication.class, args);
    }
}
