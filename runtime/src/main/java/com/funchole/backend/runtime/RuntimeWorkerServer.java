package com.funchole.backend.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Runtime Worker's IPC server. Listens on a Unix Domain Socket, accepts
 * persistent Dispatcher connections, decodes INVOKE messages, validates and
 * deduplicates them by {@code executionId}, and responds ACCEPTED.
 *
 * This is not a Function execution engine: it only accepts ownership of an
 * execution attempt. It never queries the FuncHole database and never
 * consumes JetStream - the Dispatcher remains the sole global coordinator.
 *
 * Idempotency is in-memory and per-process only; a worker restart loses all
 * dedup state (documented limitation).
 */
public final class RuntimeWorkerServer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeWorkerServer.class);

    private final ServerSocketChannel serverChannel;
    private final Path socketPath;
    private final String runtimeInstanceId;
    private final String runtimeType;
    private final RuntimeInvokeValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<UUID, RuntimeInvokeMessage> acceptedByExecutionId = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    private RuntimeWorkerServer(
            ServerSocketChannel serverChannel,
            Path socketPath,
            String runtimeInstanceId,
            String runtimeType
    ) {
        this.serverChannel = serverChannel;
        this.socketPath = socketPath;
        this.runtimeInstanceId = runtimeInstanceId;
        this.runtimeType = runtimeType;
        this.validator = new RuntimeInvokeValidator(runtimeType);
    }

    /**
     * Binds the worker's socket. Removes a stale socket file left over from
     * an unclean shutdown, but refuses to bind (and does not touch the file)
     * if another process is actually listening on it.
     */
    public static RuntimeWorkerServer bind(Path socketPath, String runtimeInstanceId, String runtimeType) throws IOException {
        prepareSocketPath(socketPath);
        ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
        return new RuntimeWorkerServer(serverChannel, socketPath, runtimeInstanceId, runtimeType);
    }

    /**
     * Starts the background accept loop. One daemon thread per accepted
     * connection; each connection is read sequentially.
     */
    public void start() {
        Thread acceptThread = new Thread(this::acceptLoop, "runtime-worker-accept-" + runtimeInstanceId);
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info(
                "Runtime worker ready: runtimeInstanceId={}, runtimeType={}, socket={}",
                runtimeInstanceId, runtimeType, socketPath
        );
    }

    public int acceptedCount() {
        return acceptedByExecutionId.size();
    }

    public boolean hasAccepted(UUID executionId) {
        return acceptedByExecutionId.containsKey(executionId);
    }

    @Override
    public void close() {
        running = false;
        try {
            serverChannel.close();
        } catch (IOException ignored) {
            // Best-effort close on shutdown.
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException exception) {
            logger.warn("Failed to remove socket file on shutdown: {}", socketPath);
        }
    }

    private static void prepareSocketPath(Path socketPath) throws IOException {
        Path parent = socketPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(socketPath)) {
            if (isLive(socketPath)) {
                throw new IllegalStateException(
                        "Refusing to bind: another live Runtime Worker is already listening on " + socketPath);
            }
            Files.delete(socketPath);
        }
    }

    private static boolean isLive(Path socketPath) {
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(socketPath));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private void acceptLoop() {
        while (running) {
            SocketChannel client;
            try {
                client = serverChannel.accept();
            } catch (IOException exception) {
                if (running) {
                    logger.warn("Runtime worker accept loop failed: {}", exception.getMessage());
                }
                return;
            }
            Thread handler = new Thread(() -> handleConnection(client), "runtime-worker-conn");
            handler.setDaemon(true);
            handler.start();
        }
    }

    private void handleConnection(SocketChannel client) {
        try (
                client;
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
                OutputStream out = Channels.newOutputStream(client)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!handleLine(line, out)) {
                    return;
                }
            }
        } catch (IOException exception) {
            logger.debug("Runtime worker connection closed: {}", exception.getMessage());
        }
    }

    /**
     * @return {@code false} if the connection should be closed (malformed or
     *         invalid INVOKE - no ACCEPTED is sent for it)
     */
    private boolean handleLine(String line, OutputStream out) throws IOException {
        RuntimeInvokeMessage message;
        try {
            message = objectMapper.readValue(line, RuntimeInvokeMessage.class);
        } catch (IOException exception) {
            logger.warn("Rejecting malformed INVOKE message: {}", exception.getMessage());
            return false;
        }

        String rejectionReason = validator.validate(message);
        if (rejectionReason != null) {
            logger.warn("Rejecting INVOKE executionId={}: {}", message.executionId(), rejectionReason);
            return false;
        }

        boolean firstAcceptance = acceptedByExecutionId.putIfAbsent(message.executionId(), message) == null;
        if (firstAcceptance) {
            logger.info(
                    "Runtime invocation accepted: executionId={}, invocationId={}, stepId={}, componentVersionId={}",
                    message.executionId(),
                    message.payload().invocationId(),
                    message.payload().stepId(),
                    message.payload().componentVersionId()
            );
        } else {
            logger.debug("Duplicate INVOKE for already-accepted executionId={}", message.executionId());
        }

        String json = objectMapper.writeValueAsString(RuntimeAcceptedMessage.of(message.executionId()));
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }
}
