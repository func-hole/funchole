package com.funchole.backend.runtime;

import com.funchole.backend.artifact.ArtifactStore;
import com.funchole.backend.artifact.LocalArtifactStore;
import com.funchole.backend.artifact.S3ArtifactStore;
import com.funchole.backend.artifact.S3ArtifactStoreConfig;
import java.net.URI;
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
        Path artifactsRoot = Path.of(readString("ARTIFACT_DIR", "artifacts/dev"));
        Path artifactCacheRoot = Path.of(readString("ARTIFACT_CACHE_DIR", "/tmp/funchole/artifact-cache"));
        String artifactStoreType = readString("ARTIFACT_STORE_TYPE", "local");
        String nodeCommand = readString("NODE_COMMAND", "node");
        Path nodeExecutorScript = Path.of(readString("NODE_EXECUTOR_SCRIPT_PATH", "node/executor.mjs"));

        ArtifactStore artifactStore = createArtifactStore(artifactStoreType, artifactsRoot, artifactCacheRoot, runtimeType);
        PersistentNodeExecutor nodeExecutor = PersistentNodeExecutor.start(nodeCommand, nodeExecutorScript);

        RuntimeWorkerServer server = RuntimeWorkerServer.bind(
                socketPath,
                runtimeInstanceId,
                runtimeType,
                artifactStore,
                nodeExecutor
        );
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            nodeExecutor.close();
        }));

        logger.info("Runtime worker running. Press Ctrl+C to stop.");
        new CountDownLatch(1).await();
    }

    private static ArtifactStore createArtifactStore(
            String artifactStoreType,
            Path artifactsRoot,
            Path artifactCacheRoot,
            String runtimeType
    ) {
        if ("s3".equalsIgnoreCase(artifactStoreType)) {
            ArtifactCache cache = new FilesystemArtifactCache(artifactCacheRoot, runtimeType);
            ArtifactStore remoteStore = new S3ArtifactStore(runtimeType, new S3ArtifactStoreConfig(
                    URI.create(readRequiredString("S3_ARTIFACT_ENDPOINT")),
                    readRequiredString("S3_ARTIFACT_BUCKET"),
                    readRequiredString("S3_ARTIFACT_ACCESS_KEY"),
                    readRequiredString("S3_ARTIFACT_SECRET_KEY"),
                    readString("S3_ARTIFACT_REGION", "us-east-1"),
                    readBoolean("S3_ARTIFACT_PATH_STYLE_ACCESS", true)
            ));
            return new CachedArtifactStore(cache, remoteStore);
        }
        if ("local".equalsIgnoreCase(artifactStoreType)) {
            return new LocalArtifactStore(artifactsRoot, runtimeType);
        }
        throw new IllegalArgumentException("Unsupported ARTIFACT_STORE_TYPE: " + artifactStoreType);
    }

    private static String readString(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String readRequiredString(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static boolean readBoolean(String name, boolean fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }
}
