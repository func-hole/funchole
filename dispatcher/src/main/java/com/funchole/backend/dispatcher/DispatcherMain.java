package com.funchole.backend.dispatcher;

import com.funchole.backend.invocation.InvocationMessagingConfig;
import com.funchole.backend.invocation.JdbcInvocationRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.nats.client.Connection;
import io.nats.client.Nats;
import javax.sql.DataSource;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DispatcherMain {
    private static final Logger logger = LoggerFactory.getLogger(DispatcherMain.class);

    private DispatcherMain() {
    }

    public static void main(String[] args) throws Exception {
        DataSource dataSource = createDataSource();
        Connection natsConnection = Nats.connect(readString("NATS_URL", "nats://localhost:4222"));
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection,
                new JdbcInvocationRegistry(dataSource)
        );
        Duration pollTimeout = Duration.ofMillis(readInt("DISPATCHER_POLL_TIMEOUT_MS", 1000));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                natsConnection.close();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            if (dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.close();
            }
        }));

        logger.info(
                "Dispatcher started. stream={}, subject={}, durable={}",
                InvocationMessagingConfig.STREAM_NAME,
                InvocationMessagingConfig.INVOCATION_READY_SUBJECT,
                InvocationMessagingConfig.DISPATCHER_DURABLE
        );

        while (!Thread.currentThread().isInterrupted()) {
            dispatcher.processNext(pollTimeout);
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
        config.setPoolName("dispatcher-db-pool");
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
