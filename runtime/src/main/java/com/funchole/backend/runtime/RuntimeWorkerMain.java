package com.funchole.backend.runtime;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RuntimeWorkerMain {
    private static final Logger logger = LoggerFactory.getLogger(RuntimeWorkerMain.class);

    private RuntimeWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path socketPath = Path.of(readString("RUNTIME_WORKER_SOCKET_PATH", "/tmp/funchole/runtime-node-dev-1.sock"));
        String runtimeInstanceId = readString("RUNTIME_INSTANCE_ID", "runtime-node-dev-1");
        String runtimeType = readString("RUNTIME_TYPE", "NODE");

        RuntimeWorkerServer server = RuntimeWorkerServer.bind(socketPath, runtimeInstanceId, runtimeType);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        logger.info("Runtime worker running. Press Ctrl+C to stop.");
        new CountDownLatch(1).await();
    }

    private static String readString(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
