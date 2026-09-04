package com.funchole.backend.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.funchole.backend")
public class FuncHoleBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FuncHoleBackendApplication.class, args);
    }
}
