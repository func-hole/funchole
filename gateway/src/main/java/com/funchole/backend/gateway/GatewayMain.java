package com.funchole.backend.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.gateway.flow.FlowResolver;
import com.funchole.backend.gateway.flow.NoopFlowResolver;
import com.funchole.backend.gateway.server.GatewayHttpHandler;
import com.funchole.backend.gateway.server.GatewayServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GatewayMain {
    private static final Logger logger = LoggerFactory.getLogger(GatewayMain.class);

    private GatewayMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = readInt("GATEWAY_PORT", 443);
        DataSource dataSource = createDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        OpenBaoCertificateLoader certificateLoader = new OpenBaoCertificateLoader(
                readString("BAO_ADDR", "http://localhost:8200"),
                readString("BAO_TOKEN", "")
        );
        GatewayRegistryLoader gatewayRegistryLoader = new GatewayRegistryLoader(dataSource, certificateLoader);
        GatewayRegistry gatewayRegistry = new GatewayRegistry(loadGatewayRegistry(gatewayRegistryLoader));
        FlowResolver flowResolver = new NoopFlowResolver();
        GatewayHttpHandler gatewayHttpHandler = new GatewayHttpHandler(objectMapper, gatewayRegistry, flowResolver);
        GatewayServer gatewayServer = new GatewayServer(port, gatewayRegistry, gatewayHttpHandler);
        ScheduledExecutorService registryRefreshExecutor = createRegistryRefreshExecutor();
        startRegistryPolling(gatewayRegistry, gatewayRegistryLoader, registryRefreshExecutor);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            gatewayServer.close();
            registryRefreshExecutor.shutdownNow();
            if (dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.close();
            }
        }));

        gatewayServer.start();
        gatewayServer.await();
    }

    private static GatewayRegistrySnapshot loadGatewayRegistry(GatewayRegistryLoader gatewayRegistryLoader) {
        int attempts = readInt("GATEWAY_REGISTRY_LOAD_ATTEMPTS", 30);
        int delayMs = readInt("GATEWAY_REGISTRY_LOAD_DELAY_MS", 2000);
        IllegalStateException lastException = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return gatewayRegistryLoader.load();
            } catch (IllegalStateException exception) {
                lastException = exception;
                logger.warn(
                        "Gateway registry load attempt {}/{} failed: {}",
                        attempt,
                        attempts,
                        exception.getMessage()
                );
                if (attempt == attempts) {
                    break;
                }

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Gateway registry loading was interrupted", interruptedException);
                }
            }
        }

        throw new IllegalStateException("Unable to load gateway registry after " + attempts + " attempts", lastException);
    }

    private static ScheduledExecutorService createRegistryRefreshExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-registry-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void startRegistryPolling(
            GatewayRegistry gatewayRegistry,
            GatewayRegistryLoader gatewayRegistryLoader,
            ScheduledExecutorService registryRefreshExecutor
    ) {
        int intervalSeconds = readInt("GATEWAY_REGISTRY_POLL_INTERVAL_SECONDS", 5);
        registryRefreshExecutor.scheduleWithFixedDelay(
                () -> refreshGatewayRegistry(gatewayRegistry, gatewayRegistryLoader),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private static void refreshGatewayRegistry(GatewayRegistry gatewayRegistry, GatewayRegistryLoader gatewayRegistryLoader) {
        try {
            GatewayRegistrySnapshot nextSnapshot = gatewayRegistryLoader.load();
            int previousSize = gatewayRegistry.size();
            int nextSize = nextSnapshot.entriesByHostname().size();
            gatewayRegistry.replace(nextSnapshot);
            if (previousSize != nextSize) {
                logger.info("Gateway registry refreshed. Registered host count changed from {} to {}", previousSize, nextSize);
            }
        } catch (Exception exception) {
            logger.warn("Gateway registry polling failed: {}", exception.getMessage());
        }
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
