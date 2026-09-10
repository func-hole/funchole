package com.funchole.backend.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.runtimeregistry.RuntimeTarget;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real local IPC {@link RuntimeExecutionGateway}: hands execution requests to
 * a Runtime Worker over a persistent Unix Domain Socket connection, framed as
 * newline-delimited JSON.
 *
 * One connection is kept per distinct {@link RuntimeTarget#socketPath()} and
 * reused across handoffs. There is no reconnect backoff/retry loop: if a
 * connection is found closed/broken on the next handoff, this gateway simply
 * opens a new one (see {@link #connectionFor(String)}). Correlation between
 * an INVOKE and its ACCEPTED response is by {@code executionId} only - never
 * by channel identity or message order - so a channel can safely carry
 * multiple in-flight requests in the future.
 *
 * Only the handoff (INVOKE -> ACCEPTED) is covered here. This gateway does
 * not wait for, or know anything about, Function execution completion.
 */
public final class IpcRuntimeExecutionGateway implements RuntimeExecutionGateway, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(IpcRuntimeExecutionGateway.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, IpcConnection> connectionsBySocketPath = new ConcurrentHashMap<>();
    private final Duration acceptTimeout;

    public IpcRuntimeExecutionGateway(Duration acceptTimeout) {
        this.acceptTimeout = acceptTimeout;
    }

    @Override
    public RuntimeExecutionAcceptance handoff(RuntimeTarget target, RuntimeExecutionRequest request) {
        if (target == null) {
            throw new IllegalArgumentException("Runtime target is required");
        }
        if (target.socketPath() == null || target.socketPath().isBlank()) {
            throw new RuntimeIpcException(
                    "Runtime target " + target.runtimeInstanceId() + " has no IPC socket path registered");
        }
        if (request == null || request.executionId() == null) {
            throw new IllegalArgumentException("Runtime execution request with executionId is required");
        }

        IpcConnection connection;
        try {
            connection = connectionFor(target.socketPath());
        } catch (IOException exception) {
            throw new RuntimeIpcException(
                    "Failed to connect to runtime worker socket " + target.socketPath(), exception);
        }

        CompletableFuture<IpcAcceptedMessage> pending = connection.registerPending(request.executionId());
        try {
            connection.writeInvoke(IpcInvokeMessage.from(request));
        } catch (IOException exception) {
            connection.close();
            connectionsBySocketPath.remove(target.socketPath(), connection);
            throw new RuntimeIpcException(
                    "Failed to write INVOKE for executionId " + request.executionId(), exception);
        }

        try {
            IpcAcceptedMessage accepted = pending.get(acceptTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return RuntimeExecutionAcceptance.accept(accepted.executionId());
        } catch (TimeoutException exception) {
            throw new RuntimeIpcException(
                    "Timed out waiting for ACCEPTED for executionId " + request.executionId(), exception);
        } catch (ExecutionException exception) {
            throw new RuntimeIpcException(
                    "IPC connection failed while waiting for ACCEPTED for executionId " + request.executionId(),
                    exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeIpcException(
                    "Interrupted while waiting for ACCEPTED for executionId " + request.executionId(), exception);
        }
    }

    /**
     * Closes all cached connections. Intended for orderly Dispatcher shutdown.
     */
    public void close() {
        connectionsBySocketPath.values().forEach(IpcConnection::close);
        connectionsBySocketPath.clear();
    }

    /**
     * Atomically returns the cached open connection for this socket path, or
     * connects a new one. Uses {@link Map#compute} (not get-then-put) so
     * concurrent handoffs to the same target cannot race into opening two
     * separate connections.
     */
    private IpcConnection connectionFor(String socketPath) throws IOException {
        try {
            return connectionsBySocketPath.compute(socketPath, (path, existing) -> {
                if (existing != null && existing.isOpen()) {
                    return existing;
                }
                try {
                    return IpcConnection.connect(path, objectMapper);
                } catch (IOException exception) {
                    throw new ConnectFailure(exception);
                }
            });
        } catch (ConnectFailure failure) {
            throw failure.cause;
        }
    }

    private static final class ConnectFailure extends RuntimeException {
        private final IOException cause;

        private ConnectFailure(IOException cause) {
            this.cause = cause;
        }
    }

    private static final class IpcConnection {
        private final String socketPath;
        private final SocketChannel channel;
        private final OutputStream out;
        private final ObjectMapper objectMapper;
        private final Object writeLock = new Object();
        private final Map<UUID, CompletableFuture<IpcAcceptedMessage>> pending = new ConcurrentHashMap<>();
        private volatile boolean open = true;

        private IpcConnection(String socketPath, SocketChannel channel, ObjectMapper objectMapper) {
            this.socketPath = socketPath;
            this.channel = channel;
            this.out = Channels.newOutputStream(channel);
            this.objectMapper = objectMapper;
        }

        static IpcConnection connect(String socketPath, ObjectMapper objectMapper) throws IOException {
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(Path.of(socketPath)));
            IpcConnection connection = new IpcConnection(socketPath, channel, objectMapper);
            connection.startReaderThread();
            return connection;
        }

        boolean isOpen() {
            return open && channel.isOpen();
        }

        CompletableFuture<IpcAcceptedMessage> registerPending(UUID executionId) {
            CompletableFuture<IpcAcceptedMessage> future = new CompletableFuture<>();
            pending.put(executionId, future);
            return future;
        }

        void writeInvoke(IpcInvokeMessage message) throws IOException {
            String json = objectMapper.writeValueAsString(message) + "\n";
            synchronized (writeLock) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        void close() {
            open = false;
            try {
                channel.close();
            } catch (IOException ignored) {
                // Best-effort close; the reader thread will observe the channel as closed.
            }
        }

        private void startReaderThread() {
            Thread reader = new Thread(this::readLoop, "ipc-runtime-gateway-" + socketPath);
            reader.setDaemon(true);
            reader.start();
        }

        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(line);
                }
            } catch (IOException ignored) {
                // Channel closed/broken; fall through to failing pending requests below.
            } finally {
                open = false;
                failAllPending();
            }
        }

        private void handleLine(String line) {
            IpcAcceptedMessage message;
            try {
                message = objectMapper.readValue(line, IpcAcceptedMessage.class);
            } catch (IOException exception) {
                logger.warn("Discarding malformed IPC response on socket {}: {}", socketPath, exception.getMessage());
                return;
            }
            if (message.executionId() == null) {
                logger.warn("Discarding IPC response with no executionId on socket {}", socketPath);
                return;
            }
            CompletableFuture<IpcAcceptedMessage> future = pending.remove(message.executionId());
            if (future == null) {
                logger.warn(
                        "Received ACCEPTED for unknown or already-completed executionId={} on socket {}",
                        message.executionId(), socketPath);
                return;
            }
            future.complete(message);
        }

        private void failAllPending() {
            RuntimeIpcException failure = new RuntimeIpcException("IPC connection to runtime worker closed: " + socketPath);
            pending.forEach((executionId, future) -> future.completeExceptionally(failure));
            pending.clear();
        }
    }
}
