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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Runtime Worker's IPC server. Listens on a Unix Domain Socket, accepts
 * persistent Dispatcher connections, decodes INVOKE messages, validates and
 * deduplicates them by {@code executionId}, responds ACCEPTED promptly, and
 * then emits a deterministic fake RESULT or ERROR.
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
    private final RuntimeTerminalMode terminalMode;
    private final long fakeCompletionDelayMillis;
    private final RuntimeInvokeValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<UUID, WorkerExecutionState> executionsByExecutionId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService completionExecutor;
    private volatile boolean running = true;

    private RuntimeWorkerServer(
            ServerSocketChannel serverChannel,
            Path socketPath,
            String runtimeInstanceId,
            String runtimeType,
            RuntimeTerminalMode terminalMode,
            long fakeCompletionDelayMillis
    ) {
        this.serverChannel = serverChannel;
        this.socketPath = socketPath;
        this.runtimeInstanceId = runtimeInstanceId;
        this.runtimeType = runtimeType;
        this.terminalMode = terminalMode;
        this.fakeCompletionDelayMillis = fakeCompletionDelayMillis;
        this.validator = new RuntimeInvokeValidator(runtimeType);
        this.completionExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "runtime-worker-fake-completion-" + runtimeInstanceId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Binds the worker's socket. Removes a stale socket file left over from
     * an unclean shutdown, but refuses to bind (and does not touch the file)
     * if another process is actually listening on it.
     */
    public static RuntimeWorkerServer bind(Path socketPath, String runtimeInstanceId, String runtimeType) throws IOException {
        return bind(socketPath, runtimeInstanceId, runtimeType, RuntimeTerminalMode.RESULT, 25);
    }

    public static RuntimeWorkerServer bind(
            Path socketPath,
            String runtimeInstanceId,
            String runtimeType,
            RuntimeTerminalMode terminalMode,
            long fakeCompletionDelayMillis
    ) throws IOException {
        prepareSocketPath(socketPath);
        ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
        return new RuntimeWorkerServer(
                serverChannel,
                socketPath,
                runtimeInstanceId,
                runtimeType,
                terminalMode,
                fakeCompletionDelayMillis
        );
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
        completionExecutor.shutdownNow();
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
                    "Runtime invocation accepted: executionId={}, invocationId={}, stepId={}, componentVersionId={}",
                    message.executionId(),
                    message.payload().invocationId(),
                    message.payload().stepId(),
                    message.payload().componentVersionId()
            );
        } else {
            logger.debug("Duplicate INVOKE for already-accepted executionId={}", message.executionId());
        }

        writeJson(out, RuntimeAcceptedMessage.of(message.executionId()));
        if (firstAcceptance) {
            scheduleFakeCompletion(state, out);
        } else if (state.terminalMessage() != null) {
            writeJson(out, state.terminalMessage());
        }
        return true;
    }

    private void scheduleFakeCompletion(WorkerExecutionState state, OutputStream out) {
        completionExecutor.schedule(
                () -> completeFakeExecution(state, out),
                fakeCompletionDelayMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private void completeFakeExecution(WorkerExecutionState state, OutputStream out) {
        RuntimeTerminalMessage terminalMessage = terminalMode == RuntimeTerminalMode.ERROR
                ? RuntimeTerminalMessage.error(state.message().executionId())
                : RuntimeTerminalMessage.result(state.message().executionId());
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
