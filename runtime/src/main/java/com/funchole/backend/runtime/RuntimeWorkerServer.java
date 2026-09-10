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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Runtime Worker's IPC server. Listens on a Unix Domain Socket, accepts
 * persistent Dispatcher connections, decodes INVOKE messages, validates and
 * deduplicates them by {@code executionId}, responds ACCEPTED promptly, and
 * then executes the pinned artifact through a persistent {@link NodeExecutor}
 * before writing the real RESULT or ERROR.
 *
 * This is not a generic Function execution engine: for this milestone it
 * only supports {@code runtimeType=NODE} and {@code componentType=FUNCTION}.
 * It never queries the FuncHole database and never consumes JetStream - the
 * Dispatcher remains the sole global coordinator.
 *
 * Idempotency is in-memory and per-process only; a worker restart loses all
 * dedup state (documented limitation).
 */
public final class RuntimeWorkerServer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeWorkerServer.class);
    private static final String SUPPORTED_COMPONENT_TYPE = "FUNCTION";

    private final ServerSocketChannel serverChannel;
    private final Path socketPath;
    private final String runtimeInstanceId;
    private final String runtimeType;
    private final ArtifactResolver artifactResolver;
    private final NodeExecutor nodeExecutor;
    private final RuntimeInvokeValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<UUID, WorkerExecutionState> executionsByExecutionId = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    private RuntimeWorkerServer(
            ServerSocketChannel serverChannel,
            Path socketPath,
            String runtimeInstanceId,
            String runtimeType,
            ArtifactResolver artifactResolver,
            NodeExecutor nodeExecutor
    ) {
        this.serverChannel = serverChannel;
        this.socketPath = socketPath;
        this.runtimeInstanceId = runtimeInstanceId;
        this.runtimeType = runtimeType;
        this.artifactResolver = artifactResolver;
        this.nodeExecutor = nodeExecutor;
        this.validator = new RuntimeInvokeValidator(runtimeType);
    }

    /**
     * Binds the worker's socket. Removes a stale socket file left over from
     * an unclean shutdown, but refuses to bind (and does not touch the file)
     * if another process is actually listening on it.
     */
    public static RuntimeWorkerServer bind(
            Path socketPath,
            String runtimeInstanceId,
            String runtimeType,
            ArtifactResolver artifactResolver,
            NodeExecutor nodeExecutor
    ) throws IOException {
        prepareSocketPath(socketPath);
        ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
        return new RuntimeWorkerServer(
                serverChannel, socketPath, runtimeInstanceId, runtimeType, artifactResolver, nodeExecutor);
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
        return executionsByExecutionId.size();
    }

    public boolean hasAccepted(UUID executionId) {
        return executionsByExecutionId.containsKey(executionId);
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

        WorkerExecutionState executionState = new WorkerExecutionState(message);
        WorkerExecutionState existing = executionsByExecutionId.putIfAbsent(message.executionId(), executionState);
        boolean firstAcceptance = existing == null;
        WorkerExecutionState state = firstAcceptance ? executionState : existing;
        if (firstAcceptance) {
            logger.info(
                    "Artifact execution accepted: executionId={}, componentVersionId={}, runtimeType={}",
                    message.executionId(),
                    message.payload().componentVersionId(),
                    message.payload().runtimeType()
            );
        } else {
            logger.debug("Duplicate INVOKE for already-accepted executionId={}", message.executionId());
        }

        writeJson(out, RuntimeAcceptedMessage.of(message.executionId()));
        if (firstAcceptance) {
            executeArtifact(state, out);
        } else if (state.terminalMessage() != null) {
            writeJson(out, state.terminalMessage());
        }
        return true;
    }

    private void executeArtifact(WorkerExecutionState state, OutputStream out) {
        RuntimeInvokeMessage message = state.message();
        RuntimeInvokePayload payload = message.payload();

        if (!SUPPORTED_COMPONENT_TYPE.equalsIgnoreCase(payload.componentType())) {
            completeAndWrite(state, out, RuntimeTerminalMessage.error(
                    message.executionId(),
                    "UNSUPPORTED_COMPONENT_TYPE",
                    "Runtime Worker only executes " + runtimeType.toUpperCase(Locale.ROOT) + " " + SUPPORTED_COMPONENT_TYPE
                            + " components; got " + payload.componentType()
            ));
            return;
        }

        Optional<ArtifactReference> artifact = artifactResolver.resolve(payload.componentId(), payload.componentVersionId());
        if (artifact.isEmpty()) {
            completeAndWrite(state, out, RuntimeTerminalMessage.error(
                    message.executionId(),
                    "ARTIFACT_NOT_FOUND",
                    "No artifact registered for componentVersionId=" + payload.componentVersionId()
            ));
            return;
        }
        logger.info(
                "Artifact resolved: executionId={}, componentVersionId={}, artifactPath={}",
                message.executionId(), payload.componentVersionId(), artifact.get().artifactPath()
        );

        NodeExecutionRequest nodeRequest = new NodeExecutionRequest(
                message.executionId(),
                payload.componentId(),
                payload.componentVersionId(),
                artifact.get().artifactPath(),
                payload.input()
        );

        nodeExecutor.execute(nodeRequest).whenComplete((result, failure) -> {
            RuntimeTerminalMessage terminalMessage = failure != null
                    ? RuntimeTerminalMessage.error(message.executionId(), "NODE_EXECUTOR_UNAVAILABLE", failure.getMessage())
                    : RuntimeTerminalMessage.from(result);
            completeAndWrite(state, out, terminalMessage);
        });
    }

    private void completeAndWrite(WorkerExecutionState state, OutputStream out, RuntimeTerminalMessage terminalMessage) {
        if (!state.complete(terminalMessage)) {
            return;
        }
        try {
            writeJson(out, terminalMessage);
            logger.info(
                    "Runtime execution completed: executionId={}, type={}",
                    terminalMessage.executionId(),
                    terminalMessage.type()
            );
        } catch (IOException exception) {
            logger.warn("Failed to write terminal runtime message for executionId={}: {}",
                    terminalMessage.executionId(), exception.getMessage());
        }
    }

    private void writeJson(OutputStream out, Object message) throws IOException {
        synchronized (out) {
            String json = objectMapper.writeValueAsString(message);
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static final class WorkerExecutionState {
        private final RuntimeInvokeMessage message;
        private volatile RuntimeTerminalMessage terminalMessage;

        private WorkerExecutionState(RuntimeInvokeMessage message) {
            this.message = message;
        }

        private RuntimeInvokeMessage message() {
            return message;
        }

        private RuntimeTerminalMessage terminalMessage() {
            return terminalMessage;
        }

        private synchronized boolean complete(RuntimeTerminalMessage terminalMessage) {
            if (this.terminalMessage != null) {
                return false;
            }
            this.terminalMessage = terminalMessage;
            return true;
        }
    }
}
