package com.funchole.backend.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal raw Unix Domain Socket test double for a Runtime Worker, used only
 * to drive {@link IpcRuntimeExecutionGateway} from the client side. Written
 * independently of the real {@code runtime} module's server on purpose - the
 * two sides of this IPC boundary only share a documented wire contract, not
 * Java code.
 */
final class FakeIpcWorker implements AutoCloseable {

    enum Behavior {
        ACCEPT,
        WRONG_EXECUTION_ID,
        SILENT,
        CLOSE_IMMEDIATELY
    }

    private final ServerSocketChannel serverChannel;
    private final Path socketPath;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<UUID> receivedExecutionIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger acceptedConnectionCount = new AtomicInteger();
    private volatile Behavior behavior = Behavior.ACCEPT;
    private volatile boolean running = true;

    private FakeIpcWorker(ServerSocketChannel serverChannel, Path socketPath) {
        this.serverChannel = serverChannel;
        this.socketPath = socketPath;
    }

    static FakeIpcWorker start() throws IOException {
        Path socketPath = Path.of("/tmp", "fh-fake-worker-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
        Files.deleteIfExists(socketPath);
        ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
        FakeIpcWorker worker = new FakeIpcWorker(serverChannel, socketPath);
        worker.acceptLoop();
        return worker;
    }

    String socketPath() {
        return socketPath.toString();
    }

    void behave(Behavior behavior) {
        this.behavior = behavior;
    }

    List<UUID> receivedExecutionIds() {
        return receivedExecutionIds;
    }

    int acceptedConnectionCount() {
        return acceptedConnectionCount.get();
    }

    private void acceptLoop() {
        Thread thread = new Thread(() -> {
            while (running) {
                SocketChannel client;
                try {
                    client = serverChannel.accept();
                } catch (IOException exception) {
                    return;
                }
                acceptedConnectionCount.incrementAndGet();
                Thread handler = new Thread(() -> handle(client), "fake-ipc-worker-conn");
                handler.setDaemon(true);
                handler.start();
            }
        }, "fake-ipc-worker-accept");
        thread.setDaemon(true);
        thread.start();
    }

    private void handle(SocketChannel client) {
        try (
                client;
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
                OutputStream out = Channels.newOutputStream(client)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode node = objectMapper.readTree(line);
                UUID executionId = UUID.fromString(node.get("executionId").asText());
                receivedExecutionIds.add(executionId);

                switch (behavior) {
                    case ACCEPT -> respond(out, executionId);
                    case WRONG_EXECUTION_ID -> respond(out, UUID.randomUUID());
                    case SILENT -> {
                        // Deliberately never respond, to exercise the client's accept timeout.
                    }
                    case CLOSE_IMMEDIATELY -> {
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
            // Connection closed by the client or the OS; nothing to clean up here.
        }
    }

    private void respond(OutputStream out, UUID executionId) throws IOException {
        String json = "{\"type\":\"ACCEPTED\",\"executionId\":\"" + executionId + "\"}\n";
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() {
        running = false;
        try {
            serverChannel.close();
        } catch (IOException ignored) {
            // Best-effort close.
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
