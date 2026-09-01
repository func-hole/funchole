package com.funchole.backend.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.gateway.server.GatewayHttpHandler;
import com.funchole.backend.gateway.server.GatewayServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

public final class GatewayMain {

    private GatewayMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = readInt("GATEWAY_PORT", 7081);
        DataSource dataSource = createDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        GatewayHttpHandler gatewayHttpHandler = new GatewayHttpHandler(objectMapper, dataSource);
        GatewayServer gatewayServer = new GatewayServer(port, gatewayHttpHandler);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            gatewayServer.close();
            if (dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.close();
            }
        }));

        gatewayServer.start();
        gatewayServer.await();
    }

    private static DataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(readString("DB_URL", "jdbc:postgresql://localhost:5432/funchole"));
        config.setUsername(readString("DB_USERNAME", "funchole"));
        config.setPassword(readString("DB_PASSWORD", "funchole"));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setPoolName("gateway-db-pool");
        return new HikariDataSource(config);
    }

    private static String readString(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int readInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }
}
